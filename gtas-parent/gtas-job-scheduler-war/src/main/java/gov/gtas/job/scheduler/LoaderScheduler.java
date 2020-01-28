/*
 * All GTAS code is Copyright 2016, The Department of Homeland Security (DHS), U.S. Customs and Border Protection (CBP).
 * 
 * Please see LICENSE.txt for details.
 */
package gov.gtas.job.scheduler;

/*
 * All GTAS code is Copyright 2016, The Department of Homeland Security (DHS), U.S. Customs and Border Protection (CBP).
 * 
 * Please see LICENSE.txt for details.
 */
import static gov.gtas.constant.GtasSecurityConstants.GTAS_APPLICATION_USERID;

import gov.gtas.enumtype.AuditActionType;
import gov.gtas.json.AuditActionData;
import gov.gtas.json.AuditActionTarget;
import gov.gtas.model.MessageStatus;
import gov.gtas.parsers.tamr.jms.TamrMessageSender;
import gov.gtas.parsers.tamr.model.TamrPassenger;
import gov.gtas.repository.MessageStatusRepository;
import gov.gtas.services.*;
import gov.gtas.services.matcher.MatchingService;
import gov.gtas.svc.TargetingService;


import java.io.File;
import java.util.Date;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Message Loader Scheduler class. Using Spring's Scheduled annotation for
 * scheduling tasks. The class reads configuration values from an external file.
 *
 */
@Component
public class LoaderScheduler {

	private static final Logger logger = LoggerFactory.getLogger(LoaderScheduler.class);

	/**
	 * The Enum InputType.
	 */
	public enum InputType {
		TWO_DIRS("two_dirs");
		private final String stringValue;

		private InputType(final String s) {
			stringValue = s;
		}

		@Override
		public String toString() {
			return stringValue;
		}
	}

	@Autowired
	private TargetingService targetingService;

	@Autowired
	private Loader loader;

	@Autowired
	private ErrorPersistenceService errorPersistenceService;

	@Autowired
	private AuditLogPersistenceService auditLogPersistenceService;

	@Autowired
	private MatchingService matchingService;

	@Autowired
	private MessageStatusRepository messageStatusRepository;

	@Autowired
	private TamrMessageSender tamrMessageSender;

	@Value("${message.dir.processed}")
	private String messageProcessedDir;

	@Value("${message.dir.working}")
	private String messageWorkingDir;

	@Value("${message.dir.error}")
	private String messageErrorDir;

	@Value("${inputType}")
	private String inputType;

	@Value("${maxNumofFiles}")
	private int maxNumofFiles;

	@Value("${tamr.enabled}")
	private Boolean tamrEnabled;

	private void processSingleFile(File f, LoaderStatistics stats, String[] primeFlightKey) throws Exception {
		logger.debug(String.format("Processing %s", f.getAbsolutePath()));
		ProcessedMessages processedMessages = loader.processMessage(f, primeFlightKey);
		int[] result = processedMessages.getProcessed();
		List<MessageStatus> messageStatusList = processedMessages.getMessageStatusList();
		messageStatusRepository.saveAll(messageStatusList);

		logger.info("GTAS Processing Completed");

		if (tamrEnabled) {
			List<TamrPassenger> passToSend = processedMessages.getTamrPassengers();
			logger.info(String.valueOf(passToSend.size()));
			tamrMessageSender.sendMessageToTamr("InboundQueue", passToSend);
		}

		if (result != null) {
			stats.incrementNumFilesProcessed();
			stats.incrementNumMessagesProcessed(result[0]);
			stats.incrementNumMessagesFailed(result[1]);
		} else {
			stats.incrementNumFilesAborted();
		}
	}

	// Method to be processed in thread generated by JMS listener
	public void receiveMessage(String text, String fileName, String[] primeFlightKey) {
		LoaderStatistics stats = new LoaderStatistics();
		logger.debug("MESSAGE RECEIVED FROM QUEUE: " + messageWorkingDir + File.separator + fileName);

		File f = new File(messageWorkingDir + File.separator + fileName);
		try {
			processSingleFile(f, stats, primeFlightKey);
			saveProcessedFile(f);
		} catch (Exception ex) {
			logger.error("Unable to process file!");
		}
	}

	// Moves the file from the working dir to the processed, returns true on
	// success.
	public boolean saveProcessedFile(File file) {
		boolean saved = false;

		if (file == null || !file.isFile())
			return saved;

		String destinationDir = messageProcessedDir;

		try {
			Utils.moveToDirectory(destinationDir, file);
		} catch (Exception ex) {
			logger.error("Unable to move file '" + file.getName() + "' to directory: " + destinationDir, ex);
		}

		return saved;
	}

	/**
	 * Writes the audit log with run statistics.
	 * 
	 * @param stats
	 *            the statistics bean.
	 */
	private void writeAuditLog(LoaderStatistics stats) {
		AuditActionTarget target = new AuditActionTarget(AuditActionType.LOADER_RUN, "GTAS Message Loader", null);
		AuditActionData actionData = new AuditActionData();
		actionData.addProperty("totalFilesProcessed", String.valueOf(stats.getNumFilesProcessed()));
		actionData.addProperty("totalFilesAborted", String.valueOf(stats.getNumFilesAborted()));
		actionData.addProperty("totalMessagesProcessed", String.valueOf(stats.getNumMessagesProcessed()));
		actionData.addProperty("totalMessagesInError", String.valueOf(stats.getNumMessagesFailed()));

		String message = "Message Loader run on " + new Date();
		auditLogPersistenceService.create(AuditActionType.LOADER_RUN, target, actionData, message,
				GTAS_APPLICATION_USERID);
	}
}
