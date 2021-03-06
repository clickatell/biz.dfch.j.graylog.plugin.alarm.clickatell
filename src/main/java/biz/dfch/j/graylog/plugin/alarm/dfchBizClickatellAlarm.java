package biz.dfch.j.graylog.plugin.alarm;

import org.graylog2.plugin.Message;
import biz.dfch.j.clickatell.ClickatellClient;
import biz.dfch.j.clickatell.rest.accountbalance.AccountBalanceResponse;
import biz.dfch.j.clickatell.rest.coverage.CoverageResponse;
import biz.dfch.j.clickatell.rest.message.MessageResponse;
import com.google.common.collect.Maps;
import org.graylog2.plugin.MessageSummary;
import org.graylog2.plugin.alarms.AlertCondition;
import org.graylog2.plugin.alarms.callbacks.AlarmCallback;
import org.graylog2.plugin.alarms.callbacks.AlarmCallbackConfigurationException;
import org.graylog2.plugin.alarms.callbacks.AlarmCallbackException;
import org.graylog2.plugin.configuration.Configuration;
import org.graylog2.plugin.configuration.ConfigurationException;
import org.graylog2.plugin.configuration.ConfigurationRequest;
import org.graylog2.plugin.configuration.fields.BooleanField;
import org.graylog2.plugin.configuration.fields.ConfigurationField;
import org.graylog2.plugin.configuration.fields.NumberField;
import org.graylog2.plugin.configuration.fields.TextField;
import org.graylog2.plugin.streams.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InvalidObjectException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 *  
 * @author Ronald Rink
 * @version 1.0.0
 * @since 2015-02-19
 *  
 * This is the plugin. Your class should implement one of the existing plugin
 * interfaces. (i.e. AlarmCallback, MessageInput, MessageOutput)
 *  
 */
public class dfchBizClickatellAlarm implements AlarmCallback
{
    private static final String CONFIG_AUTH_TOKEN = "CONFIG_AUTH_TOKEN";
    private static final String CONFIG_RECIPIENTS = "CONFIG_RECIPIENTS";
    private static final String CONFIG_FIELDS = "CONFIG_FIELDS";
    private static final String CONFIG_INCLUDE_FIELD_NAMES = "CONFIG_INCLUDE_FIELD_NAMES";
    private static final String CONFIG_MAX_LENGTH = "CONFIG_MAX_LENGTH";
    private static final String CONFIG_MAX_CREDITS = "CONFIG_MAX_CREDITS";
    private static final String CONFIG_MAX_PARTS = "CONFIG_MAX_PARTS";
    private static final String CONFIG_STATIC_TEXT = "CONFIG_STATIC_TEXT";
    private static final String CONFIG_RESULT_DESCRIPTION = "CONFIG_RESULT_DESCRIPTION";

    private static final Logger LOG = LoggerFactory.getLogger(dfchBizClickatellAlarm.class);
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private Configuration configuration;
    List<String> recipients;
    List<String> fields;
    ClickatellClient clickatellClient;

    /**
     * This method is called by Graylog when the plugin is executed the first time (as a result of an alert condition).
     * @param configuration The Configuration object containing the settings the user specified when create the plugin instance.
     * @throws AlarmCallbackConfigurationException This exception is thrown when an incomplete or invalid combination of configuration settings has been specified.
     * @see #getRequestedConfiguration()
     */
    @Override
    public void initialize(final Configuration configuration) throws AlarmCallbackConfigurationException {
        try {
            LOG.debug("Verifying configuration ...");
            
            this.configuration = configuration;

            String configAuthToken = configuration.getString("CONFIG_AUTH_TOKEN");
            if(null == configAuthToken || configAuthToken.isEmpty())
            {
                throw new AlarmCallbackConfigurationException(String.format("configAuthToken: Parameter validation FAILED. Value cannot be null or empty."));
            }

            recipients = Arrays.asList(configuration.getString(CONFIG_RECIPIENTS).split("\\s*,\\s*"));
            if(configuration.getString(CONFIG_RECIPIENTS).isEmpty() || 0 >= recipients.size() || recipients.isEmpty())
            {
                throw new AlarmCallbackConfigurationException(String.format("CONFIG_RECIPIENTS: Parameter validation FAILED. You have to specify at least one recipient."));
            }

            fields = Arrays.asList(configuration.getString(CONFIG_FIELDS).split("\\s*,\\s*"));
            if(configuration.getString(CONFIG_FIELDS).isEmpty() || 0 >= fields.size() || fields.isEmpty())
            {
                LOG.warn(String.format("No fields were specified. Using the following default fields: '<timestamp>', '<stream>', '<message>'."));
                fields = new ArrayList<String>();
                fields.add("<timestamp>");
                fields.add("<stream>");
                fields.add("<source>");
                fields.add("<message>");
            }

            if(0 > configuration.getInt("CONFIG_MAX_LENGTH"))
            {
                LOG.error(String.format("CONFIG_MAX_LENGTH: Parameter validation FAILED. Field must be equal or greater than zero."));
            }

            if(0 > configuration.getInt("CONFIG_MAX_CREDITS"))
            {
                LOG.warn(String.format("CONFIG_MAX_CREDITS: Parameter validation FAILED. Field must be equal or greater than zero."));
            }

            if(0 > configuration.getInt("CONFIG_MAX_PARTS"))
            {
                LOG.warn(String.format("CONFIG_MAX_PARTS: Parameter validation FAILED. Field must be equal or greater than zero."));
            }

            LOG.info("Verifying configuration SUCCEEDED.");

            LOG.debug("Connecting to Clickatell ...");

            clickatellClient = new ClickatellClient(configAuthToken);
            AccountBalanceResponse accountBalanceResponse = clickatellClient.getBalance();
            LOG.info(String.format("%s: current balance for alarm is '%s'.", (new dfchBizClickatellAlarmMetaData()).getName(), accountBalanceResponse.getData().getBalance()));
            for(String recipient : recipients)
            {
                CoverageResponse coverageResponse = clickatellClient.getCoverage(recipient);
                if(!coverageResponse.getData().isRoutable()) {
                    LOG.error(String.format("Sending message to '%s' is outside coverage. No message will be sent to this recipient.", recipient));
                }
            }
            LOG.info("Connecting to Clickatell SUCCEEDED.");

            isRunning.set(true);
        }
        catch (AlarmCallbackConfigurationException ex)
        {
            LOG.error("Connecting to Clickatell FAILED.", ex);
            throw ex;
        }
        catch (Exception ex)
        {
            LOG.error("Connecting to Clickatell FAILED.", ex);
            throw new AlarmCallbackConfigurationException(ex.getMessage());
        }
    }

    @Override
    public ConfigurationRequest getRequestedConfiguration()
    {
        final ConfigurationRequest configurationRequest = new ConfigurationRequest();

        configurationRequest.addField(new TextField(
                        CONFIG_AUTH_TOKEN, "Clickatell AuthToken", "",
                        "AuthenticationToken for the Clickatell REST API",
                        ConfigurationField.Optional.NOT_OPTIONAL,
                        TextField.Attribute.IS_PASSWORD)
        );

        configurationRequest.addField(new TextField(
                        CONFIG_RECIPIENTS, "Recipients of short message", "",
                        "Comma separated list of number in international format, eg 27999112345. No '00', ' ', '+' or '-', just numbers",
                        ConfigurationField.Optional.NOT_OPTIONAL)
        );

        configurationRequest.addField(new BooleanField(
                        CONFIG_RESULT_DESCRIPTION, "Include result description in short message", true,
                        "Set to true to include result description in short message, or set to false to omit result description.")
        );

        configurationRequest.addField(new TextField(
                        CONFIG_FIELDS, "Fields to send in short message", "",
                        "Comma separated list of fields to send as message text, eg <message>, <id>, <timestamp>, <source>, <stream> or user defined fields. Built-in fields have to be surrounded by '<>'",
                        ConfigurationField.Optional.OPTIONAL)
        );

        configurationRequest.addField(new BooleanField(
                        CONFIG_INCLUDE_FIELD_NAMES, "Include field names in short message", true,
                        "Set to true to include field names in short message, or set to false to omit field names and only send field contents.")
        );

        configurationRequest.addField(new NumberField(
                        CONFIG_MAX_LENGTH, "MaxLength", 0,
                        "Maximum length of short message",
                        ConfigurationField.Optional.OPTIONAL)
        );

        configurationRequest.addField(new NumberField(
                        CONFIG_MAX_CREDITS, "MaxCredits", 0,
                        "Maximum credits to spend on a short message",
                        ConfigurationField.Optional.OPTIONAL)
        );

        configurationRequest.addField(new NumberField(
                        CONFIG_MAX_PARTS, "MaxParts", 0,
                        "Maximum number of parts a short message can consist of",
                        ConfigurationField.Optional.OPTIONAL)
        );

        configurationRequest.addField(new TextField(
                        CONFIG_STATIC_TEXT, "Static text that prepends the short message", "",
                        "You can optionally define a phrase that will be sent with every short message.",
                        ConfigurationField.Optional.OPTIONAL)
        );

        return configurationRequest;
    }

    @Override
    public void call(final Stream stream, final AlertCondition.CheckResult result) throws AlarmCallbackException
    {
        if(!this.isRunning.get()) 
        {
            return;
        }
        String streamTitle = stream.getTitle();
        if(null == streamTitle || streamTitle.isEmpty())
        {
            throw new AlarmCallbackException(String.format("streamTitle: Parameter validation FAILED. Value cannot be null or empty."));
        }
        try
        {
            StringBuilder sb = new StringBuilder(configuration.getInt("CONFIG_MAX_LENGTH"));
            String staticText = configuration.getString("CONFIG_STATIC_TEXT");
            int maxLength = configuration.getInt("CONFIG_MAX_LENGTH");
            if(null != staticText && !staticText.isEmpty())
            {
                sb.append(staticText);
                sb.append(" ");
            }
            if(configuration.getBoolean("CONFIG_RESULT_DESCRIPTION"))
            {
                sb.append(result.getResultDescription());
                sb.append(" ");
            }

            List<MessageSummary> messageSummarys = result.getMatchingMessages();
            if(0 < messageSummarys.size())
            {
                MessageSummary messageSummary = messageSummarys.get(0);
                Map<String, Object> messageFields = messageSummary.getFields();
                
                for (String fieldName : fields)
                {
                    switch(fieldName)
                    {
                        case "<id>":
                            if(configuration.getBoolean("CONFIG_INCLUDE_FIELD_NAMES"))
                            {
                                sb.append("id: ");
                            }
                            sb.append(messageSummary.getId());
                            sb.append(";");
                            break;
                        case "<message>":
                            if(configuration.getBoolean("CONFIG_INCLUDE_FIELD_NAMES"))
                            {
                                sb.append("message: ");
                            }
                            sb.append(messageSummary.getMessage());
                            sb.append(";");
                            break;
                        case "<source>":
                            if(configuration.getBoolean("CONFIG_INCLUDE_FIELD_NAMES"))
                            {
                                sb.append("source: ");
                            }
                            sb.append(messageSummary.getSource());
                            sb.append(";");
                            break;
                        case "<timestamp>":
                            if(configuration.getBoolean("CONFIG_INCLUDE_FIELD_NAMES"))
                            {
                                sb.append("timestamp: ");
                            }
                            sb.append(messageSummary.getTimestamp());
                            sb.append(";");
                            break;
                        case "<stream>":
                            if(configuration.getBoolean("CONFIG_INCLUDE_FIELD_NAMES"))
                            {
                                sb.append("stream: ");
                            }
                            sb.append(streamTitle);
                            sb.append(";");
                            break;
                        default:
                            if(!messageFields.containsKey(fieldName))
                            {
                                LOG.warn(String.format("%s: field name does not exist. Skipping ...", fieldName));
                                continue;
                            }
                            if(configuration.getBoolean("CONFIG_INCLUDE_FIELD_NAMES"))
                            {
                                sb.append(fieldName);
                                sb.append(": ");
                            }
                            String fieldValue = messageFields.get(fieldName).toString();
                            sb.append(fieldValue);
                            sb.append(";");
                            break;
                    }
                    if(0 < maxLength && maxLength < sb.length())
                    {
                        break;
                    }
                }
            }
            if(0 < maxLength && maxLength < sb.length())
            {
                LOG.warn(String.format("CONFIG_MAX_LENGTH: Generated message contains '%d' characters and exceeds configured maximum of '%d' characters. Truncating message ...", sb.length(), maxLength));
                sb.setLength(maxLength);
            }

            LOG.debug(sb.toString());
            List<MessageResponse> messageResponses = clickatellClient.sendMessage(
                    recipients,
                    sb.toString(),
                    configuration.getInt("CONFIG_MAX_CREDITS"),
                    configuration.getInt("CONFIG_MAX_PARTS")
            );
        }
        catch (IOException ex)
        {
            LOG.error("IOException occurred.", ex);
            ex.printStackTrace();
//            throw ex;
        }
        catch (Exception ex)
        {
            LOG.error("Exception occurred.", ex);
            ex.printStackTrace();
            throw ex;
        }
    }

    @Override
    public Map<String, Object> getAttributes()
    {
        return Maps.transformEntries(configuration.getSource(), new Maps.EntryTransformer<String, Object, Object>() {
            @Override
            public Object transformEntry(String key, Object value) {
                return key + "-" + value;
            }
        });
    }
    
    @Override
    public String getName()
    {
        return (new dfchBizClickatellAlarmMetaData()).getName();
    };

    @Override
    public void checkConfiguration() throws ConfigurationException
    {
        LOG.info("checkConfiguration");
    }
}

/**
 *
 *
 * Copyright 2015 Ronald Rink, d-fens GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
