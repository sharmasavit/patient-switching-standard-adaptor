package uk.nhs.adaptors.pss.translator.mhs;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.ReactiveHttpOutputMessage;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import io.netty.handler.ssl.SslContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.netty.http.client.HttpClient;
import uk.nhs.adaptors.pss.translator.config.MhsOutboundConfiguration;
import uk.nhs.adaptors.pss.translator.mhs.model.OutboundMessage;
import uk.nhs.adaptors.pss.translator.service.RequestBuilderService;

@Slf4j
@Component
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class MhsRequestBuilder {

    private static final String ODS_CODE = "ods-code";
    private static final String INTERACTION_ID = "Interaction-Id";
    private static final String MHS_OUTBOUND_EXTRACT_CORE_INTERACTION_ID = "RCMR_IN010000UK05";
    private static final String MHS_OUTBOUND_COMMON_INTERACTION_ID = "COPC_IN000001UK01";
    private static final String MHS_OUTBOUND_APPLICATION_ACKNOWLEDGMENT_INTERACTION_ID = "MCCI_IN010000UK13";
    private static final String CORRELATION_ID = "Correlation-Id";
    private static final String WAIT_FOR_RESPONSE = "wait-for-response";
    private static final String FALSE = "false";
    private static final String CONTENT_TYPE = "Content-type";
    private static final String MESSAGE_ID = "Message-Id";

    private final RequestBuilderService requestBuilderService;
    private final MhsOutboundConfiguration mhsOutboundConfiguration;

    public WebClient.RequestHeadersSpec<?> buildSendEhrExtractRequest(
        String conversationId, String toOdsCode, OutboundMessage outboundMessage, String messageId) {
        return buildSendRequest(conversationId, toOdsCode, outboundMessage, MHS_OUTBOUND_EXTRACT_CORE_INTERACTION_ID, messageId);
    }

    public WebClient.RequestHeadersSpec<?> buildSendContinueRequest(
        String conversationId, String toOdsCode, OutboundMessage outboundMessage, String messageId) {
        return buildSendRequest(conversationId, toOdsCode, outboundMessage, MHS_OUTBOUND_COMMON_INTERACTION_ID, messageId);
    }

    public WebClient.RequestHeadersSpec<?> buildSendACKRequest(
            String conversationId, String toOdsCode, OutboundMessage outboundMessage, String messageId) {
        return buildSendRequest(conversationId, toOdsCode, outboundMessage,
                MHS_OUTBOUND_APPLICATION_ACKNOWLEDGMENT_INTERACTION_ID, messageId);
    }

    private WebClient.RequestHeadersSpec<?> buildSendRequest(
        String conversationId, String toOdsCode, OutboundMessage outboundMessage, String interactionId, String messageId) {
        SslContext sslContext = requestBuilderService.buildSSLContext();
        HttpClient httpClient = HttpClient.create().secure(t -> t.sslContext(sslContext));
        WebClient client = buildWebClient(httpClient);

        WebClient.RequestBodySpec uri = client.method(HttpMethod.POST).uri("/");

        BodyInserter<Object, ReactiveHttpOutputMessage> bodyInserter = BodyInserters.fromValue(outboundMessage);

        return uri
            .accept(APPLICATION_JSON)
            .header(ODS_CODE, toOdsCode)
            .header(CONTENT_TYPE, APPLICATION_JSON_VALUE)
            .header(INTERACTION_ID, interactionId)
            .header(WAIT_FOR_RESPONSE, FALSE)
            .header(CORRELATION_ID, conversationId)
            .header(MESSAGE_ID, messageId)
            .body(bodyInserter);
    }

    private WebClient buildWebClient(HttpClient httpClient) {
        return WebClient
            .builder()
            .exchangeStrategies(requestBuilderService.buildExchangeStrategies())
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .baseUrl(mhsOutboundConfiguration.getUrl())
            .build();
    }
}
