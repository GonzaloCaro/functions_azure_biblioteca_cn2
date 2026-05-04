package com.functions.azure;

import com.microsoft.azure.functions.annotation.*;
import com.microsoft.azure.functions.*;

import java.util.Optional;

public class EventGridApp {

    @FunctionName("PublishEventToGrid")
    public HttpResponseMessage run(
            @HttpTrigger(name = "req", methods = {HttpMethod.POST}, authLevel = AuthorizationLevel.ANONYMOUS) HttpRequestMessage<Optional<String>> request,
            @EventGridOutput(name = "outputEvent", topicEndpointUri = "EventGridTopicEndpoint", topicKeySetting = "EventGridTopicKey") OutputBinding<String> outputEvent,
            final ExecutionContext context) {

        context.getLogger().info("PublishEventToGrid triggered.");

        String body = request.getBody().orElse("");

        if (body.isEmpty()) {
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST).body("Please pass a JSON body to publish.").build();
        }

        // EventGrid requires a specific JSON array format. We pass the raw string if it's already an array,
        // or wrap it if it's a single event object. Usually, EventGrid payload needs: id, subject, data, eventType, eventTime, dataVersion.
        // Assuming client sends full EventGridEvent JSON array or object.
        
        outputEvent.setValue(body);

        return request.createResponseBuilder(HttpStatus.OK).body("Event published successfully.").build();
    }
}
