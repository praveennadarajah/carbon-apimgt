/*
*Copyright (c) 2005-2010, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
*WSO2 Inc. licenses this file to you under the Apache License,
*Version 2.0 (the "License"); you may not use this file except
*in compliance with the License.
*You may obtain a copy of the License at
*
*http://www.apache.org/licenses/LICENSE-2.0
*
*Unless required by applicable law or agreed to in writing,
*software distributed under the License is distributed on an
*"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
*KIND, either express or implied.  See the License for the
*specific language governing permissions and limitations
*under the License.
*/

package org.wso2.carbon.apimgt.keymgt.service;


import io.opentracing.SpanContext;
import org.apache.axis2.AxisFault;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.transport.http.HTTPConstants;
import org.apache.axis2.wsdl.WSDLConstants;
import org.apache.commons.httpclient.Header;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.APIIdentifier;
import org.wso2.carbon.apimgt.api.model.URITemplate;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.dao.ApiMgtDAO;
import org.wso2.carbon.apimgt.impl.dao.constants.SQLConstants;
import org.wso2.carbon.apimgt.impl.dto.APIKeyValidationInfoDTO;
import org.wso2.carbon.apimgt.impl.utils.APIMgtDBUtil;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.apimgt.keymgt.APIKeyMgtException;
import org.wso2.carbon.apimgt.keymgt.handlers.KeyValidationHandler;
import org.wso2.carbon.apimgt.keymgt.internal.ServiceReferenceHolder;
import org.wso2.carbon.apimgt.keymgt.util.APIKeyMgtDataHolder;
import org.wso2.carbon.apimgt.keymgt.util.APIKeyMgtUtil;
import org.wso2.carbon.apimgt.tracing.TracingServiceImpl;
import org.wso2.carbon.apimgt.tracing.TracingSpan;
import org.wso2.carbon.apimgt.tracing.TracingTracer;
import org.wso2.carbon.apimgt.tracing.Util;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.core.AbstractAdmin;
import org.wso2.carbon.metrics.manager.MetricManager;
import org.wso2.carbon.metrics.manager.Timer;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;
import org.wso2.carbon.utils.multitenancy.MultitenantUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 *
 */
public class APIKeyValidationService extends AbstractAdmin {
    private static final Log log = LogFactory.getLog(APIKeyValidationService.class);
    private static KeyValidationHandler keyValidationHandler;
    private TracingSpan spanContext;
    TracingSpan validateMainspan;
    TracingTracer tracer = Util.getGlobalTracer();

    public APIKeyValidationService() {
        try {
            if (keyValidationHandler == null) {

                KeyValidationHandler validationHandler = (KeyValidationHandler) APIUtil.getClassForName
                        (ServiceReferenceHolder.getInstance().
                                getAPIManagerConfigurationService().getAPIManagerConfiguration().
                                getFirstProperty(APIConstants.API_KEY_MANGER_VALIDATIONHANDLER_CLASS_NAME).trim()).newInstance();
                log.info("Initialised KeyValidationHandler instance successfully");
                if (keyValidationHandler == null) {
                    synchronized (this) {
                        keyValidationHandler = validationHandler;
                    }
                }
            }
        } catch (InstantiationException e) {
            log.error("Error while instantiating class" + e.toString());
        } catch (IllegalAccessException e) {
            log.error("Error while accessing class" + e.toString());
        } catch (ClassNotFoundException e) {
            log.error("Error while creating keyManager instance" + e.toString());
        }
    }

    /**
     * Validates the access tokens issued for a particular user to access an API.
     *
     * @param context     Requested context
     * @param accessToken Provided access token
     * @return APIKeyValidationInfoDTO with authorization info and tier info if authorized. If it is not
     * authorized, tier information will be <pre>null</pre>
     * @throws APIKeyMgtException Error occurred when accessing the underlying database or registry.
     */
    public APIKeyValidationInfoDTO validateKey(String context, String version, String accessToken,
                                               String requiredAuthenticationLevel, String clientDomain,
                                               String matchingResource, String httpVerb)
            throws APIKeyMgtException, APIManagementException {

        Timer timer = MetricManager.timer(org.wso2.carbon.metrics.manager.Level.INFO, MetricManager.name(
                APIConstants.METRICS_PREFIX, this.getClass().getSimpleName(), "VALIDATE_MAIN"));
        Timer.Context timerContext = timer.start();

        MessageContext axis2MessageContext = MessageContext.getCurrentMessageContext();
//        TracingTracer tracer = Util.getGlobalTracer();
//        Map<String, String> tracerSpecificCarrier = new HashMap<String, String>();
        Map headersMap = null;
        String activityID = null;
        try {
            if (axis2MessageContext != null) {
                MessageContext responseMessageContext = axis2MessageContext.getOperationContext().
                        getMessageContext(WSDLConstants.MESSAGE_LABEL_OUT_VALUE);
                if (log.isDebugEnabled()) {
                    List headersList = new ArrayList();
                    Object headers = axis2MessageContext.getProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS);
                    if (headers != null && headers instanceof Map) {
                        headersMap = (Map) headers;
                        activityID = (String) headersMap.get("activityID");
                        spanContext = Util.extract(tracer, headersMap);
                    }
                    if(headersMap != null) {
                        headersList.add(new Header("activityID", (String) headersMap.get("activityID")));
                    }

                    responseMessageContext.setProperty(HTTPConstants.HTTP_HEADERS, headersList);
                }
            }
        } catch (AxisFault axisFault) {
            throw new APIKeyMgtException("Error while building response messageContext: " + axisFault.getLocalizedMessage());
        }
        validateMainspan = Util.startSpan("Validate_Main", spanContext, tracer, null);
//        Util.setTag(validateMainspan, "span.kind", "server");
        Util.setTag(validateMainspan, "span.kind", "client");
        Util.setTag(validateMainspan, "span.kind", "server");
        Util.setTag(validateMainspan, "component", "GRPC");
        Util.setTag(validateMainspan,"http.method", "GET");
        Util.setTag(validateMainspan, "http.url", "https://domain.net/path/to?resource=here");
        Util.setTag(validateMainspan, "message_bus.destination", "topic name");
        Util.setTag(validateMainspan,"peer.address", "address");
        Util.setTag(validateMainspan,"peer.hostname", "opentracing.io");
        Util.setTag(validateMainspan,"peer.ipv4", "127.0.0.1");
        Util.setTag(validateMainspan,"peer.ipv6", "2001:0db8:85a3:0000:0000:8a2e:0370:7334");
        Util.setTag(validateMainspan,"peer.hostname", "peer.service");
        Util.setTag(validateMainspan, "service", "yololo");
        if (log.isDebugEnabled()) {
            String logMsg = "KeyValidation request from gateway: requestTime= "
                    + new SimpleDateFormat("[yyyy.MM.dd HH:mm:ss,SSS zzz]").format(new Date()) + " , for:"
                    + context + " with accessToken=" + accessToken;
            if (activityID != null) {
                logMsg = logMsg + " , transactionId=" + activityID;
            }
            log.debug(logMsg);
        }
        TokenValidationContext validationContext = new TokenValidationContext();
        validationContext.setAccessToken(accessToken);
        validationContext.setClientDomain(clientDomain);
        validationContext.setContext(context);
        validationContext.setHttpVerb(httpVerb);
        validationContext.setMatchingResource(matchingResource);
        validationContext.setRequiredAuthenticationLevel(requiredAuthenticationLevel);
        validationContext.setValidationInfoDTO(new APIKeyValidationInfoDTO());
        validationContext.setVersion(version);

        TracingSpan span = Util.startSpan("Get_Access_Token_Cache_Key", validateMainspan,tracer, null);
        String cacheKey = APIUtil.getAccessTokenCacheKey(accessToken,
                                                         context, version, matchingResource, httpVerb, requiredAuthenticationLevel);

        validationContext.setCacheKey(cacheKey);
        Util.finishSpan(span);

        TracingSpan keyValDTO = Util.startSpan("Fetching_API_Key_Validation_DTO", validateMainspan, tracer, null);
        APIKeyValidationInfoDTO infoDTO = APIKeyMgtUtil.getFromKeyManagerCache(cacheKey);
        Util.finishSpan(keyValDTO);

        if (infoDTO != null) {
            validationContext.setCacheHit(true);
            log.debug("APIKeyValidationInfoDTO fetched from cache. Setting cache hit to true...");
            validationContext.setValidationInfoDTO(infoDTO);
        }

        log.debug("Before calling Validate Token method...");

        Timer timer2 = MetricManager.timer(org.wso2.carbon.metrics.manager.Level.INFO, MetricManager.name(
                APIConstants.METRICS_PREFIX, this.getClass().getSimpleName(), "VALIDATE_TOKEN"));
        Timer.Context timerContext2 = timer2.start();
        TracingSpan validateTokenSpan = Util.startSpan("Validate_Token", validateMainspan, tracer, null);
        boolean state = keyValidationHandler.validateToken(validationContext);
        timerContext2.stop();
        Util.finishSpan(validateTokenSpan);
        log.debug("State after calling validateToken ... " + state);

        if (state) {
            Timer timer3 = MetricManager.timer(org.wso2.carbon.metrics.manager.Level.INFO, MetricManager.name(
                    APIConstants.METRICS_PREFIX, this.getClass().getSimpleName(), "VALIDATE_SUBSCRIPTION"));
            Timer.Context timerContext3 = timer3.start();
            TracingSpan validateSubscriptionSpan = Util.startSpan("Validate_Subscription", validateMainspan, tracer, null);
            state = keyValidationHandler.validateSubscription(validationContext);
            timerContext3.stop();
            Util.finishSpan(validateSubscriptionSpan);
        }

        log.debug("State after calling validateSubscription... " + state);

        if (state) {
            Timer timer4 = MetricManager.timer(org.wso2.carbon.metrics.manager.Level.INFO, MetricManager.name(
                    APIConstants.METRICS_PREFIX, this.getClass().getSimpleName(), "VALIDATE_SCOPES"));
            Timer.Context timerContext4 = timer4.start();
            TracingSpan validateScopeSpan = Util.startSpan("Validate_Scopes", validateMainspan, tracer, null);
            state = keyValidationHandler.validateScopes(validationContext);
            timerContext4.stop();
            Util.finishSpan(validateScopeSpan);
        }

        log.debug("State after calling validateScopes... " + state);

        if (state && APIKeyMgtDataHolder.isJwtGenerationEnabled() &&
                validationContext.getValidationInfoDTO().getEndUserName() != null && !validationContext.isCacheHit()) {
            Timer timer5 = MetricManager.timer(org.wso2.carbon.metrics.manager.Level.INFO, MetricManager.name(
                    APIConstants.METRICS_PREFIX, this.getClass().getSimpleName(), "GENERATE_JWT"));
            Timer.Context timerContext5 = timer5.start();
            TracingSpan generateJWTSpan = Util.startSpan("Generate_JWT", validateMainspan, tracer, null);
            keyValidationHandler.generateConsumerToken(validationContext);
            timerContext5.stop();
            Util.finishSpan(generateJWTSpan);
        }
        log.debug("State after calling generateConsumerToken... " + state);

        if (!validationContext.isCacheHit()) {
            TracingSpan keyCache = Util.startSpan("Write_to_key_manager_cache", validateMainspan, tracer, null);
            APIKeyMgtUtil.writeToKeyManagerCache(cacheKey, validationContext.getValidationInfoDTO());
            Util.finishSpan(keyCache);
        }

        TracingSpan span1 = Util.startSpan("publishing_from KM _to_gateway", validateMainspan, tracer, null);
        if (log.isDebugEnabled() && axis2MessageContext != null) {
            logMessageDetails(axis2MessageContext, validationContext.getValidationInfoDTO());
        }

        if (log.isDebugEnabled()) {
            log.debug("APIKeyValidationInfoDTO before returning : " + validationContext.getValidationInfoDTO());
            log.debug("KeyValidation response from keymanager to gateway for access token:" + accessToken + " at "
                    + new SimpleDateFormat("[yyyy.MM.dd HH:mm:ss,SSS zzz]").format(new Date()));
        }
        Util.finishSpan(span1);
        timerContext.stop();
        Util.finishSpan(validateMainspan);
        return validationContext.getValidationInfoDTO();
    }

    /**
     * Return the URI Templates for an API
     *
     * @param context Requested context
     * @param version API Version
     * @return APIKeyValidationInfoDTO with authorization info and tier info if authorized. If it is not
     * authorized, tier information will be <pre>null</pre>
     * @throws APIKeyMgtException Error occurred when accessing the underlying database or registry.
     */
    public ArrayList<URITemplate> getAllURITemplates(String context, String version)
            throws APIKeyMgtException, APIManagementException {
        Timer timer6 = MetricManager.timer(org.wso2.carbon.metrics.manager.Level.INFO, MetricManager.name(
                APIConstants.METRICS_PREFIX, this.getClass().getSimpleName(), "GET_URI_TEMPLATE"));
        Timer.Context timerContext6 = timer6.start();
//        MessageContext axis2MessageContext = MessageContext.getCurrentMessageContext();
//        Map headersMap = null;
//        Object headers = axis2MessageContext.getProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS);
//        if (headers != null && headers instanceof Map) {
//            headersMap = (Map) headers;
//            spanContext = Util.extract(tracer, headersMap);
//        }
//        spanContext = Util.extract(tracer, headersMap);
//        TracingSpan getAllURITemplatesSpan = Util.startSpan("get_All_URI_Templates", spanContext, tracer);
        if (log.isDebugEnabled()) {
            log.debug("getAllURITemplates request from gateway to keymanager: requestTime="
                    + new SimpleDateFormat("[yyyy.MM.dd HH:mm:ss,SSS zzz]").format(new Date())
                    + " ,for:" + context);
        }
        ArrayList<URITemplate> templates = ApiMgtDAO.getInstance().getAllURITemplates(context, version);
        if (log.isDebugEnabled()) {
            log.debug("getAllURITemplates response from keyManager to gateway for:" + context + " at "
                    + new SimpleDateFormat("[yyyy.MM.dd HH:mm:ss,SSS zzz]").format(new Date()));
        }
        timerContext6.stop();
//        Util.finishSpan(getAllURITemplatesSpan);
        return templates;
    }

    private void logMessageDetails(MessageContext messageContext, APIKeyValidationInfoDTO apiKeyValidationInfoDTO) {
        String applicationName = apiKeyValidationInfoDTO.getApplicationName();
        String endUserName = apiKeyValidationInfoDTO.getEndUserName();
        String consumerKey = apiKeyValidationInfoDTO.getConsumerKey();
        Boolean isAuthorize = apiKeyValidationInfoDTO.isAuthorized();
        //Do not change this log format since its using by some external apps
        String logMessage = "";
        if (applicationName != null) {
            logMessage = " , appName=" + applicationName;
        }
        if (endUserName != null) {
            logMessage = logMessage + " , userName=" + endUserName;
        }
        Map headers = (Map) messageContext.getProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS);
        String logID = (String) headers.get("activityID");
        if (logID != null) {
            logMessage = logMessage + " , transactionId=" + logID;
        }
        if (consumerKey != null) {
            logMessage = logMessage + " , consumerKey=" + consumerKey;
        }
        logMessage = logMessage + " , isAuthorized=" + isAuthorize;
        logMessage = logMessage + " , responseTime=" + new Date(System.currentTimeMillis());

        log.debug("OAuth token response from keyManager to gateway: " + logMessage);
    }

    /**
     * validate access token for websocket handshake
     *
     * @param context context of the API
     * @param version version of the API
     * @param accessToken access token of the request
     * @return api information
     * @throws APIKeyMgtException
     * @throws APIManagementException
     */
    public APIKeyValidationInfoDTO validateKeyforHandshake(String context, String version,
                                                           String accessToken)
            throws APIKeyMgtException, APIManagementException {
        boolean defaultVersionInvoked = false;
        APIKeyValidationInfoDTO info = new APIKeyValidationInfoDTO();
        info.setAuthorized(false);
        TokenValidationContext validationContext = new TokenValidationContext();
        validationContext.setAccessToken(accessToken);
        validationContext.setContext(context);
        validationContext.setValidationInfoDTO(new APIKeyValidationInfoDTO());
        validationContext.setVersion(version);
        validationContext.setRequiredAuthenticationLevel("Any");
        boolean state = keyValidationHandler.validateToken(validationContext);
        ApiMgtDAO dao = ApiMgtDAO.getInstance();
        if (state) {
            info.setAuthorized(true);
            info.setValidityPeriod(validationContext.getTokenInfo().getValidityPeriod());
            info.setIssuedTime(validationContext.getTokenInfo().getIssuedTime());
            String def_version = isDefaultVersionInvoked(validationContext.getContext());
            if (def_version != null) {
                defaultVersionInvoked = true;
                version = def_version;
                context += "/" + def_version;
                validationContext.setVersion(version);
                validationContext.setContext(context);
            }
            info = dao.validateSubscriptionDetails(info, validationContext.getContext(),
                                               validationContext.getVersion(),
                                               validationContext.getTokenInfo().getConsumerKey(), defaultVersionInvoked);

            if (defaultVersionInvoked) {
                info.setApiName(info.getApiName() + "*" + version);
            }
        }

        info.setConsumerKey(validationContext.getTokenInfo().getConsumerKey());
        info.setEndUserName(validationContext.getTokenInfo().getEndUserName());
        return info;
    }

    /**
     * find out whether the Default API version is invoked
     *
     * @param context context of API accessing
     * @return Default API version. return null if not default version
     * @throws APIManagementException
     */
    private String isDefaultVersionInvoked(String context) throws APIManagementException {
        ApiMgtDAO dao = ApiMgtDAO.getInstance();
        String[] APIDetails = dao.getAPIDetailsByContext(context);
        String apiName = APIDetails[0];
        String apiProvider = APIDetails[1];
        if (!(apiName.equalsIgnoreCase("") || apiProvider.equalsIgnoreCase(""))) {
            return dao.getDefaultVersion(new APIIdentifier(apiProvider, apiName, ""));
        }
        return null;
    }
}
