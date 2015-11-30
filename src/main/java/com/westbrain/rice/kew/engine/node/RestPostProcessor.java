package com.westbrain.rice.kew.engine.node;

import org.apache.commons.lang3.StringUtils;
import org.kuali.rice.core.api.config.property.Config;
import org.kuali.rice.core.api.config.property.ConfigContext;
import org.kuali.rice.kew.api.KewApiServiceLocator;
import org.kuali.rice.kew.framework.postprocessor.DocumentRouteLevelChange;
import org.kuali.rice.kew.framework.postprocessor.DocumentRouteStatusChange;
import org.kuali.rice.kew.framework.postprocessor.IDocumentEvent;
import org.kuali.rice.kew.framework.postprocessor.ProcessDocReport;
import org.kuali.rice.kew.postprocessor.DefaultPostProcessor;

public class RestPostProcessor extends DefaultPostProcessor {
	
	private RestPostProcessorDelegate delegate;
	
	@Override
	public ProcessDocReport doRouteStatusChange(DocumentRouteStatusChange statusChangeEvent) throws Exception {
		initializeDelegate(statusChangeEvent).handleRouteStatusChange(statusChangeEvent);
		return new ProcessDocReport(true);
	}

	@Override
	public ProcessDocReport doRouteLevelChange(DocumentRouteLevelChange levelChangeEvent) throws Exception {
		initializeDelegate(levelChangeEvent).handleRouteLevelChange(levelChangeEvent);
		return new ProcessDocReport(true);
	}

	private RestPostProcessorDelegate initializeDelegate(IDocumentEvent event) {
		if (this.delegate == null) {
			String documentTypeName = KewApiServiceLocator.getWorkflowDocumentService().getDocumentTypeName(event.getDocumentId());
			Config config = ConfigContext.getCurrentContextConfig();
			String prefix = "RestPostProcessor." + documentTypeName + ".";
			String targetUrlProp = prefix + "targetUrl";
			String usernameProp = prefix + "username";
			String passwordProp = prefix + "password";
			String targetUrl = config.getProperty(targetUrlProp);
			String username = config.getProperty(usernameProp);
			String password = config.getProperty(passwordProp);
			if (StringUtils.isBlank(targetUrl) || StringUtils.isBlank(username) || StringUtils.isBlank(password)) {
				throw new IllegalStateException("Failed to locate configuration for one of the following properties, they must all be set: " +
						targetUrlProp +", " + usernameProp + ", " + passwordProp);
			}
			this.delegate = new RestPostProcessorDelegate(targetUrl, username, password);
		}
		return this.delegate;
	}

}
