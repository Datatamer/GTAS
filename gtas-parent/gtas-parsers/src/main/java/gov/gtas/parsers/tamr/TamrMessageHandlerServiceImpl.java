package gov.gtas.parsers.tamr;

import java.util.Date;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import gov.gtas.enumtype.HitTypeEnum;
import gov.gtas.model.Passenger;
import gov.gtas.model.PassengerIDTag;
import gov.gtas.model.PendingHitDetails;
import gov.gtas.model.watchlist.WatchlistItem;
import gov.gtas.parsers.tamr.model.TamrDerogHit;
import gov.gtas.parsers.tamr.model.TamrHistoryCluster;
import gov.gtas.parsers.tamr.model.TamrHistoryClusterAction;
import gov.gtas.parsers.tamr.model.TamrMessage;
import gov.gtas.parsers.tamr.model.TamrTravelerResponse;
import gov.gtas.repository.PassengerIDTagRepository;
import gov.gtas.repository.PassengerRepository;
import gov.gtas.repository.PendingHitDetailRepository;
import gov.gtas.repository.watchlist.WatchlistItemRepository;

@Component
public class TamrMessageHandlerServiceImpl implements TamrMessageHandlerService {

    private final Logger logger = LoggerFactory.getLogger(TamrMessageHandlerServiceImpl.class);

    private PassengerIDTagRepository passengerIDTagRepository;
    
    private WatchlistItemRepository watchlistItemRepository;
    
    private PendingHitDetailRepository pendingHitDetailRepository;
    
    private PassengerRepository passengerRepository;
    
    @Value("${tamr.derog_hit.title}")
    String derogHitTitle;
    
    @Value("${tamr.derog_hit.description}")
    String derogHitDescription;
    
    public TamrMessageHandlerServiceImpl(
            PassengerIDTagRepository passengerIDTagRepository,
            WatchlistItemRepository watchlistItemRepository,
            PendingHitDetailRepository pendingHitDetailRepository,
            PassengerRepository passengerRepository) {
        this.passengerIDTagRepository = passengerIDTagRepository;
        this.watchlistItemRepository = watchlistItemRepository;
        this.pendingHitDetailRepository = pendingHitDetailRepository;
        this.passengerRepository = passengerRepository;
    }

    /**
     * Handle responses to Tamr QUERY requests. This handles both the "derog
     * matches" and "traveler history" responses.
     */
    @Override
    public void handleQueryResponse(TamrMessage response) {
        if (this.checkRecordErrors(response)) return;

        for (TamrTravelerResponse travelerResponse: response.getTravelerQuery()) {
            if (travelerResponse.getTamrId() != null) {
                this.updateTamrId(travelerResponse.getGtasId(),
                        travelerResponse.getTamrId(),
                        travelerResponse.getVersion());
            }
            for (TamrDerogHit derogHit: travelerResponse.getDerogIds()) {
                this.createPendingHit(travelerResponse.getGtasId(), derogHit);
            }
        }
    }
    
    /**
     * Creates an instance of the PendingHitDetails model in GTAS based on a
     * derog hit from Tamr.
     */
    private void createPendingHit(String gtasIdStr, TamrDerogHit derogHit) {
        long gtasId;
        try {
            gtasId = Long.parseLong(gtasIdStr);
        } catch (NumberFormatException e) {
            logger.warn("Tamr returned derog hit for passenger with invalid " +
                    "ID \"{}\".", gtasIdStr);
            return;
        }
        PendingHitDetails pendingHit = new PendingHitDetails(); 

        pendingHit.setTitle(derogHitTitle);
        pendingHit.setDescription(derogHitDescription);

        pendingHit.setHitEnum(HitTypeEnum.PARTIAL_WATCHLIST);
        pendingHit.setHitType(pendingHit.getHitEnum().toString());

        // Try to get watchlist item based on derogId from Tamr.
        long watchlistItemId;
        try {
            watchlistItemId = Long.parseLong(derogHit.getDerogId()); 
        } catch (NumberFormatException e) {
            logger.warn("Tamr returned derog hit for watchlist entry with " +
                    "invalid ID \"{}\".", derogHit.getDerogId());
            return;
        }
        Optional<WatchlistItem> watchlistItem =
                watchlistItemRepository.findById(watchlistItemId);
        if (watchlistItem.isPresent()) {
            pendingHit.setHitMakerId(watchlistItem.get().getId());
        } else {
            logger.warn("Tamr returned derog hit for nonexistent watchlist " +
                    "entry with ID {}.", watchlistItemId);
            return;
        }
        
        pendingHit.setPercentage(derogHit.getScore()); 

        // Tamr doesn't return any details about the matching algorithm,
        // so leave this empty.
        pendingHit.setRuleConditions("");
        
        // Try to find passenger in GTAS so we can get the associated flight.
        Optional<Passenger> passenger = passengerRepository.findById(gtasId);
        if (passenger.isPresent()) {
            pendingHit.setFlightId(passenger.get().getFlight().getId());
        } else {
            logger.warn("Tamr returned derog hit for nonexistent passenger " +
                    "with ID {}.", gtasId);
            return;
        }

        pendingHit.setPassengerId(gtasId);

        pendingHit.setCreatedDate(new Date());
       
        // Persist to database.
        pendingHitDetailRepository.save(pendingHit);
    }

    /**
     * Handles an acknowledgement response from Tamr. This response is received
     * for requests that don't give any particular response data.
     */
    @Override
    public void handleAcknowledgeResponse(TamrMessage response) {
        if (this.checkRecordErrors(response)) {
            // Record errors are already logged.
        } else if (response.getAcknowledgment() == true) {
            logger.info("{} request to Tamr acknowledged",
                    response.getMessageType());
        } else if (response.getAcknowledgment() == false) {
            logger.error("Error with {} request to Tamr: {}",
                    response.getMessageType(), response.getError());
        } else {
            logger.warn("{} message received from Tamr with no " +
                    "\"acknowledgment\" key. Ignoring...");
        }
    }

    /**
     * Handles TH.CLUSTERS and TH.DELTAS messages from Tamr. These both require
     * similar functionality: updating the Tamr cluster IDs.
     */
    @Override
    public void handleTamrIdUpdate(TamrMessage message) {
        // The "error" field can be set on these.
        if (message.getError() != null) {
            logger.error("Tamr error in {} message: {}",
                    message.getMessageType(), message.getError());
            return;
        }

        for (TamrHistoryCluster cluster: message.getHistoryClusters()) {
            if (cluster.getAction() == null ||
                    cluster.getAction() == TamrHistoryClusterAction.UPDATE) {
                this.updateTamrId(cluster.getGtasId(),
                        cluster.getTamrId(), cluster.getVersion());
            } else if (cluster.getAction() == TamrHistoryClusterAction.DELETE) {
                this.updateTamrId(cluster.getGtasId(),
                        null, cluster.getVersion());
            } else {
                logger.warn("Unknown history cluster action \"{}\" received " +
                        "from Tamr for passenger {}. Ignoring...",
                        cluster.getAction(), cluster.getGtasId());
            }
        }
    }
    
    /**
     * Updates the tamrId for the traveler in GTAS with the given gtasId. This
     * also checks to see if the traveler exists in GTAS; if they do not, it
     * issues a warning.
     */
    private void updateTamrId(String gtasIdStr, String tamrId, int version) {
        long gtasId;
        try {
            gtasId = Long.parseLong(gtasIdStr);
        } catch (NumberFormatException e) {
            logger.warn("Unable to update tamrId of passenger with invalid " +
                    "ID \"{}\".", gtasIdStr);
            return;
        }
        PassengerIDTag currentPassengerIdTag =
                passengerIDTagRepository.findByPaxId(gtasId);
        if (currentPassengerIdTag == null) {
            logger.warn("Unable to update tamrId of nonexistent passenger " +
                    "ID tag {}.", gtasId);
        } else {
            passengerIDTagRepository.updateTamrId(gtasId, tamrId);
        }
    }
    
    /**
     * Handles ERROR messages from Tamr. These can happen during Tamr
     * processing or for any error unrelated to a request.
     */
    @Override
    public void handleErrorResponse(TamrMessage response) {
        logger.error("{} message received from Tamr: {}",
                response.getMessageType(), response.getError());
    }
    
    /**
     * Checks to see if recordErrors are present on the given response. If so,
     * returns true and logs a warning message.
     */
    private boolean checkRecordErrors(TamrMessage response) {
        boolean hasRecordErrors = response.getRecordErrors() != null &&
                response.getRecordErrors().size() > 0;
        if (hasRecordErrors) {
            ObjectMapper mapper = new ObjectMapper();
            try {
                String recordErrorsJson = mapper
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(response.getRecordErrors());
                logger.warn("Received recordErrors from Tamr for {} " +
                        "request. recordErrors = {}",
                        response.getMessageType(), recordErrorsJson);
            } catch (JsonProcessingException e) {
                logger.warn("Received recordErrors from Tamr for {} " +
                        "request. Unable to display errors.",
                        response.getMessageType());
            }
        }
        return hasRecordErrors;
    }
}
