package gov.gtas.parsers.tamr.jms;

import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.Session;
import org.springframework.stereotype.Service;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessageCreator;
import org.springframework.stereotype.Component;

import gov.gtas.parsers.tamr.jms.TamrQueueConfig;

@Component
public class TamrMessageSender {

	private final Logger logger = LoggerFactory.getLogger(TamrMessageSender.class);

	@Autowired
	JmsTemplate jmsTemplateFile;

	@Autowired
	TamrQueueConfig queueConfig;

	public ConnectionFactory connectionFactory() {
		// Add tamr connection details here.
		return new ActiveMQConnectionFactory("");
	}

	public boolean sendMessageToTamr(String queue, String messageContent) throws Exception {
		logger.info("############### Attempting to craft tamr message .... ################");
		logger.info("############### Sending to Queue: " + queue + " .... ################");
		jmsTemplateFile.setDefaultDestinationName(queue);
		jmsTemplateFile.setConnectionFactory(queueConfig.cachingConnectionFactory());

		jmsTemplateFile.send(new MessageCreator() {
			@Override
			public Message createMessage(Session session) throws JMSException {
				Message message = session.createTextMessage("{\"passengers\":" + messageContent + "}");
				message.setJMSType("QUERY");
				return message;
			}
		});
		return true;
	}
}
