package com.westbrain.rice.kew.engine.node;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.log4j.Logger;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.map.ObjectMapper;
import org.kuali.rice.core.api.exception.RiceRuntimeException;
import org.kuali.rice.kew.framework.postprocessor.DocumentRouteLevelChange;
import org.kuali.rice.kew.framework.postprocessor.DocumentRouteStatusChange;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 *
 * @author Eric Westfall
 */
class RestPostProcessorDelegate {
	
	private static final Logger LOG = Logger.getLogger(RestPostProcessorDelegate.class);

	private final EventLogSender eventLogSender;
	
	public RestPostProcessorDelegate(String targetUrl, String username, String password) {
		this.eventLogSender = new EventLogSender(targetUrl, username, password);
	}
	
	public void handleRouteLevelChange(DocumentRouteLevelChange routeLevelChange) throws Exception {
		notifyEvent(routeLevelChange.getDocumentId(), toJson(routeLevelChange));
	}

	public void handleRouteStatusChange(DocumentRouteStatusChange routeStatusChange) throws Exception {
		notifyEvent(routeStatusChange.getDocumentId(), toJson(routeStatusChange));
	}
	
	private String toJson(DocumentRouteLevelChange routeLevelChange) throws IOException {
		return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(routeLevelChange);
	}
	
	private String toJson(DocumentRouteStatusChange routeStatusChange) throws IOException {
		return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(routeStatusChange);
	}
	
	private void notifyEvent(String documentId, String json) throws Exception {
		LOG.info("Notifying of event: " + json);
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			LOG.info("Transaction synchronization is active, adding to event log.");
			List<String> eventLog = EventLogSynchronization.bindAndGetEventLog(eventLogSender);
			eventLog.add(json);
		} else {
			LOG.info("No transaction synchronization active, sending event immediately.");
			eventLogSender.send(Collections.singletonList(json));
		}
	}
		
	static class EventLogSynchronization extends TransactionSynchronizationAdapter {
		
		private static final String EVENT_LOG_KEY = "eventLog";
		
		private final EventLogSender eventLogSender;
		
		EventLogSynchronization(EventLogSender eventLogSender) {
			this.eventLogSender = eventLogSender;
		}
		
		@Override
		public void afterCompletion(int status) {
			RestPostProcessorDelegate.LOG.info("Transaction completed");
			try {
				if (status == STATUS_COMMITTED) {
					RestPostProcessorDelegate.LOG.info("Transaction was committed, sending events.");
					List<String> eventLog = (List<String>)TransactionSynchronizationManager.getResource(EVENT_LOG_KEY);
					eventLogSender.send(eventLog);
				} else {
					RestPostProcessorDelegate.LOG.info("Transaction was not committed, status was " + status +", not sending events.");
				}
			} catch (IOException e) {
				throw new RuntimeException("Failed to send the event log.", e);
			} finally {
				TransactionSynchronizationManager.unbindResource(EVENT_LOG_KEY);
			}
		}
		
		public static List<String> bindAndGetEventLog(EventLogSender eventLogSender) {
			if (!TransactionSynchronizationManager.hasResource(EVENT_LOG_KEY)) {
				TransactionSynchronizationManager.bindResource(EVENT_LOG_KEY, new ArrayList<String>());
				TransactionSynchronizationManager.registerSynchronization(new EventLogSynchronization(eventLogSender));
			}
			return (List<String>)TransactionSynchronizationManager.getResource(EVENT_LOG_KEY);
		}
		
	}
	
	static class EventLogSender {
		
		private final String targetUrl;
		private final String username;
		private final String password;
		
		EventLogSender(String targetUrl, String username, String password) {
			this.targetUrl = targetUrl;
			this.username = username;
			this.password = password;
		}
		
		void send(List<String> eventLog) throws IOException {
			CloseableHttpClient httpClient = HttpClients.createDefault();
			try {

				JsonFactory factory = new JsonFactory();
				StringWriter writer = new StringWriter();
				JsonGenerator generator = factory.createJsonGenerator(writer);
				generator.writeStartObject();
				generator.writeArrayFieldStart("events");
				for (String eventJson : eventLog) {
					generator.writeRawValue(eventJson);
				}
				generator.writeEndArray();
				generator.writeEndObject();
				generator.close();
								
				CredentialsProvider credsProvider = new BasicCredentialsProvider();
				credsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(username, password));
				HttpClientContext context = HttpClientContext.create();
				context.setCredentialsProvider(credsProvider);
				
				RestPostProcessorDelegate.LOG.info("Posting " + eventLog.size() + " events to " + targetUrl);
				HttpPost httpPost = new HttpPost(targetUrl);
				httpPost.setEntity(new StringEntity(writer.toString(), ContentType.APPLICATION_JSON));
				CloseableHttpResponse response = httpClient.execute(httpPost, context);
				int statusCode = response.getStatusLine().getStatusCode();
				LOG.info("Status code was " + statusCode + " when invoking " + targetUrl);
				if (statusCode >= 400) {
					throw new RiceRuntimeException("Failed to invoke " + targetUrl +", response code was " + statusCode);
				}
				
			} finally {
			    httpClient.close();
			}
		}
				
	}
	
}
