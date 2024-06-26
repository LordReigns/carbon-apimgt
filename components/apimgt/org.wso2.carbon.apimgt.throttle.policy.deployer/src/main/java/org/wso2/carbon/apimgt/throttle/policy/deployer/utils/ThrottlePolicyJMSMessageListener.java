/*
 *  Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.carbon.apimgt.throttle.policy.deployer.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import org.apache.axiom.util.UIDGenerator;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONObject;
import org.wso2.carbon.apimgt.eventing.EventPublisherEvent;
import org.wso2.carbon.apimgt.eventing.EventPublisherType;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.dto.EventHubConfigurationDto;
import org.wso2.carbon.apimgt.impl.notifier.events.APIPolicyEvent;
import org.wso2.carbon.apimgt.impl.notifier.events.ApplicationPolicyEvent;
import org.wso2.carbon.apimgt.impl.notifier.events.ApplicationPolicyResetEvent;
import org.wso2.carbon.apimgt.impl.notifier.events.GlobalPolicyEvent;
import org.wso2.carbon.apimgt.impl.notifier.events.PolicyEvent;
import org.wso2.carbon.apimgt.impl.notifier.events.SubscriptionPolicyEvent;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.apimgt.throttle.policy.deployer.PolicyRetriever;
import org.wso2.carbon.apimgt.throttle.policy.deployer.dto.ApiPolicy;
import org.wso2.carbon.apimgt.throttle.policy.deployer.dto.ApplicationPolicy;
import org.wso2.carbon.apimgt.throttle.policy.deployer.dto.GlobalPolicy;
import org.wso2.carbon.apimgt.throttle.policy.deployer.dto.SubscriptionPolicy;
import org.wso2.carbon.apimgt.throttle.policy.deployer.exception.ThrottlePolicyDeployerException;
import org.wso2.carbon.apimgt.throttle.policy.deployer.internal.ServiceReferenceHolder;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.TextMessage;
import javax.jms.Topic;

/**
 * Throttle policy JMS event listener class.
 */
public class ThrottlePolicyJMSMessageListener implements MessageListener {

    private static final Log log = LogFactory.getLog(ThrottlePolicyJMSMessageListener.class);
    private static String streamID = "org.wso2.throttle.request.stream:1.0.0";

    private final PolicyRetriever policyRetriever = new PolicyRetriever();
    private final ScheduledExecutorService policyRetrievalScheduler = Executors.newSingleThreadScheduledExecutor(
            new PolicyRetrieverThreadFactory());
    private final EventHubConfigurationDto eventHubConfigurationDto = ServiceReferenceHolder.getInstance()
            .getAPIMConfiguration().getEventHubConfigurationDto();

    public void onMessage(Message message) {

        try {
            if (eventHubConfigurationDto.hasEventWaitingTime()) {
                long timeLeft = message.getJMSTimestamp() + eventHubConfigurationDto.getEventWaitingTime()
                        - System.currentTimeMillis();
                if (log.isDebugEnabled()) {
                    log.debug("Event Hub waiting time: " + timeLeft);
                }
                if (timeLeft > 0) {
                    Thread.sleep(timeLeft);
                }
            }
            if (message != null) {
                if (log.isDebugEnabled()) {
                    log.debug("Event received in JMS Event Receiver - " + message);
                }
                Topic jmsDestination = (Topic) message.getJMSDestination();
                if (message instanceof TextMessage) {
                    String textMessage = ((TextMessage) message).getText();
                    JsonNode payloadData = new ObjectMapper().readTree(textMessage).path(APIConstants.EVENT_PAYLOAD).
                            path(APIConstants.EVENT_PAYLOAD_DATA);

                    if (APIConstants.TopicNames.TOPIC_NOTIFICATION.equalsIgnoreCase(jmsDestination.getTopicName())) {
                        if (payloadData.get(APIConstants.EVENT_TYPE).asText() != null) {
                            /*
                             * This message contains notification
                             * eventType - type of the event
                             * timestamp - system time of the event published
                             * event - event data
                             */
                            if (log.isDebugEnabled()) {
                                log.debug("Event received from the topic of " + jmsDestination.getTopicName());
                            }
                            handleNotificationMessage(payloadData.get(APIConstants.EVENT_TYPE).asText(),
                                    payloadData.get(APIConstants.EVENT_PAYLOAD).asText());
                        }
                    }
                } else {
                    log.warn("Event dropped due to unsupported message type " + message.getClass());
                }
            } else {
                log.warn("Dropping the empty/null event received through jms receiver");
            }
        } catch (JMSException | JsonProcessingException e) {
            log.error("JMSException occurred when processing the received message ", e);
        } catch (InterruptedException e) {
            log.error("Error occurred while waiting to retrieve artifacts from event hub", e);
        }
    }

    private void handleNotificationMessage(String eventType, String encodedEvent) {

        byte[] eventDecoded = Base64.decodeBase64(encodedEvent);
        String eventJson = new String(eventDecoded, StandardCharsets.UTF_8);

        if (APIConstants.EventType.POLICY_CREATE.toString().equals(eventType)
                || APIConstants.EventType.POLICY_UPDATE.toString().equals(eventType)
                || APIConstants.EventType.POLICY_DELETE.toString().equals(eventType)
        ) {

            boolean updatePolicy = APIConstants.EventType.POLICY_CREATE.toString().equals(eventType)
                    || APIConstants.EventType.POLICY_UPDATE.toString().equals(eventType);
            boolean deletePolicy = APIConstants.EventType.POLICY_DELETE.toString().equals(eventType);
            Runnable task = null;
            PolicyEvent event = new Gson().fromJson(eventJson, PolicyEvent.class);
            if (event.getPolicyType() == APIConstants.PolicyType.SUBSCRIPTION) {
                // handle subscription policies
                SubscriptionPolicyEvent policyEvent = new Gson().fromJson(eventJson, SubscriptionPolicyEvent.class);
                if (!(APIConstants.UNLIMITED_TIER.equalsIgnoreCase(policyEvent.getPolicyName())
                        || APIConstants.DEFAULT_SUB_POLICY_ASYNC_UNLIMITED.
                        equalsIgnoreCase(policyEvent.getPolicyName())
                        || APIConstants.DEFAULT_SUB_POLICY_ASYNC_WH_UNLIMITED.
                        equalsIgnoreCase(policyEvent.getPolicyName()))) {
                    task = () -> {
                        try {
                            if (updatePolicy) {
                                SubscriptionPolicy subscriptionPolicy = policyRetriever.getSubscriptionPolicy(
                                        policyEvent.getPolicyName(), policyEvent.getTenantDomain());
                                PolicyUtil.deployPolicy(subscriptionPolicy, policyEvent);
                            } else if (deletePolicy) {
                                PolicyUtil.undeployPolicy(policyEvent);
                            }
                        } catch (ThrottlePolicyDeployerException e) {
                            log.error("Error in retrieving subscription policy metadata from the database", e);
                        }
                    };
                }

            } else if (event.getPolicyType() == APIConstants.PolicyType.APPLICATION) {
                // handle application policies
                ApplicationPolicyEvent policyEvent = new Gson().fromJson(eventJson, ApplicationPolicyEvent.class);
                if (!APIConstants.UNLIMITED_TIER.equalsIgnoreCase(policyEvent.getPolicyName())) {
                    task = () -> {
                        try {
                            if (updatePolicy) {
                                ApplicationPolicy applicationPolicy = policyRetriever.getApplicationPolicy(
                                        policyEvent.getPolicyName(), policyEvent.getTenantDomain());
                                PolicyUtil.deployPolicy(applicationPolicy, policyEvent);
                            } else if (deletePolicy) {
                                PolicyUtil.undeployPolicy(policyEvent);
                            }
                        } catch (ThrottlePolicyDeployerException e) {
                            log.error("Error in retrieving application policy metadata from the database", e);
                        }
                    };
                }
            } else if (event.getPolicyType() == APIConstants.PolicyType.API) {
                // handle API policies
                APIPolicyEvent policyEvent = new Gson().fromJson(eventJson, APIPolicyEvent.class);
                if (!APIConstants.UNLIMITED_TIER.equalsIgnoreCase(policyEvent.getPolicyName())) {

                    task = () -> {
                        try {
                            if (updatePolicy) {
                                ApiPolicy apiPolicy = policyRetriever.getApiPolicy(
                                        policyEvent.getPolicyName(), policyEvent.getTenantDomain());
                                PolicyUtil.deployPolicy(apiPolicy, policyEvent);
                            } else if (deletePolicy) {
                                PolicyUtil.undeployPolicy(policyEvent);
                            }
                        } catch (ThrottlePolicyDeployerException e) {
                            log.error("Error in retrieving API policy metadata from the database", e);
                        }
                    };
                }
            } else if (event.getPolicyType() == APIConstants.PolicyType.GLOBAL) {
                // handle global policies
                GlobalPolicyEvent policyEvent = new Gson().fromJson(eventJson, GlobalPolicyEvent.class);
                task = () -> {

                    try {
                        if (updatePolicy) {
                            GlobalPolicy globalPolicy = policyRetriever.getGlobalPolicy(
                                    policyEvent.getPolicyName(), policyEvent.getTenantDomain());
                            PolicyUtil.deployPolicy(globalPolicy, policyEvent);
                        } else if (deletePolicy) {
                            PolicyUtil.undeployPolicy(policyEvent);
                        }
                    } catch (ThrottlePolicyDeployerException e) {
                        log.error("Error in retrieving Global policy metadata from the database", e);
                    }
                };
            }
            if (task != null) {
                policyRetrievalScheduler.schedule(task, 1, TimeUnit.MILLISECONDS);
            }
        } else if (APIConstants.EventType.POLICY_RESET.toString().equals(eventType)) {
            PolicyEvent event = new Gson().fromJson(eventJson, PolicyEvent.class);
            String applicationLevelThrottleKey = null;
            String applicationLevelTier = null;
            String authorizedUser = null;
            String subscriberTenantDomain = null;
            String applicationId = null;

            if (event.getPolicyType() == APIConstants.PolicyType.APPLICATION) {
                ApplicationPolicyResetEvent applicationEvent = new Gson().fromJson(eventJson,
                        ApplicationPolicyResetEvent.class);
                applicationLevelThrottleKey = applicationEvent.getAppId() + ":" + applicationEvent.getUserId() + "@"
                        + applicationEvent.getTenantDomain();
                applicationLevelTier = applicationEvent.getAppTier();
                authorizedUser = applicationEvent.getUserId();
                subscriberTenantDomain = applicationEvent.getTenantDomain();
                applicationId = applicationEvent.getAppId();
            }

            //Decoded event string to be logged in the case of failures and debugging
            String loggingEvent = event.toString();
            JSONObject jsonObMap = new JSONObject();
            jsonObMap.put(APIConstants.POLICY_RESET, true);
            Object[] objects = new Object[] { UIDGenerator.generateURNString(), applicationLevelThrottleKey,
                    applicationLevelTier, null, null, null, null, null, null, authorizedUser, null, null,
                    subscriberTenantDomain, null, applicationId, null, jsonObMap.toString() };
            EventPublisherEvent resetEvent = new EventPublisherEvent(streamID,
                    System.currentTimeMillis(), objects, loggingEvent);
            resetEvent.setOrgId(event.getTenantDomain());

            if (log.isDebugEnabled()) {
                log.debug(
                        "Publishing Application Throttle Reset data to traffic-manager for the application with ID: "
                                + applicationId);
            }
            APIUtil.publishEvent(EventPublisherType.NOTIFICATION, resetEvent, loggingEvent);
        }
    }
}

