package uk.nhs.adaptors.pss.translator.amqp;

import static java.nio.charset.StandardCharsets.UTF_8;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

import static uk.nhs.adaptors.common.enums.QueueMessageType.TRANSFER_REQUEST;
import static uk.nhs.adaptors.common.util.FileUtil.readResourceAsString;
import static uk.nhs.adaptors.connector.model.MigrationStatus.CONTINUE_REQUEST_ACCEPTED;
import static uk.nhs.adaptors.connector.model.MigrationStatus.COPC_FAILED;
import static uk.nhs.adaptors.connector.model.MigrationStatus.EHR_EXTRACT_REQUEST_ACCEPTED;
import static uk.nhs.adaptors.connector.model.MigrationStatus.EHR_EXTRACT_REQUEST_ERROR;
import static uk.nhs.adaptors.connector.model.MigrationStatus.EHR_GENERAL_PROCESSING_ERROR;
import static uk.nhs.adaptors.connector.model.MigrationStatus.REQUEST_RECEIVED;

import java.time.Duration;
import java.util.List;

import org.jdbi.v3.core.ConnectionException;
import org.json.JSONException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.HttpHeaders;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.SneakyThrows;
import uk.nhs.adaptors.common.model.TransferRequestMessage;
import uk.nhs.adaptors.connector.model.MigrationStatus;
import uk.nhs.adaptors.pss.translator.config.PssQueueProperties;
import uk.nhs.adaptors.pss.translator.exception.MhsServerErrorException;
import uk.nhs.adaptors.pss.translator.service.MhsClientService;
import uk.nhs.adaptors.pss.translator.task.SendACKMessageHandler;
import uk.nhs.adaptors.pss.translator.task.SendContinueRequestHandler;
import uk.nhs.adaptors.pss.translator.task.SendNACKMessageHandler;
import uk.nhs.adaptors.pss.util.BaseEhrHandler;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@ExtendWith({SpringExtension.class, MockitoExtension.class})
@DirtiesContext
@AutoConfigureMockMvc
public class ServiceFailureIT extends BaseEhrHandler {

    private static final String LOSING_ASID = "LOSING_ASID";
    private static final String WINNING_ASID = "WINNING_ASID";
    private static final String STUB_BODY = "test Body";
    private static final int THIRTY_SECONDS = 30000;
    private static final long TWO_MINUTES_LONG = 2L;

    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PssQueueProperties pssQueueProperties;

    @Mock
    private HttpHeaders httpHeaders;
    @SpyBean
    private MhsClientService mhsClientService;
    @SpyBean
    private SendContinueRequestHandler sendContinueRequestHandler;
    @SpyBean
    private SendACKMessageHandler sendACKMessageHandler;
    @SpyBean
    private SendNACKMessageHandler sendNACKMessageHandler;
    @SpyBean
    private MhsDlqPublisher mhsDlqPublisher;

    @BeforeEach
    public void setupIgnoredPaths() {
        setIgnoredJsonPaths(List.of(
            "id",
            "entry[0].resource.id",
            "entry[0].resource.identifier[0].value",
            "entry[*].resource.id",
            "entry[*].resource.subject.reference",
            "entry[*].resource.patient.reference",
            "entry[*].resource.performer[0].reference",
            "entry[*].resource.content[0].attachment.title",
            "entry[*].resource.content[0].attachment.url",
            "entry[*].resource.description"
        ));
    }

    @Test
    public void When_SendingInitialRequest_WithMhsOutboundServerError_Expect_MigrationLogHasRequestError() {
        var conversationId = generateConversationId();
        var patientNhsNumber = generatePatientNhsNumber();

        doThrow(getInternalServerErrorException())
            .when(mhsClientService).send(any());

        sendRequestToPssQueue(conversationId, patientNhsNumber);

        await().until(() -> hasMigrationStatus(EHR_EXTRACT_REQUEST_ERROR, conversationId));

        verify(mhsClientService, timeout(THIRTY_SECONDS).times(pssQueueProperties.getMaxRedeliveries() + 1)
        ).send(any());

        assertThat(getCurrentMigrationStatus(conversationId))
            .isEqualTo(EHR_EXTRACT_REQUEST_ERROR);
    }

    @Test
    public void When_ReceivingEhrExtract_WithMhsOutboundServerError_Expect_MigrationHasProcessingError() {
        doThrow(MhsServerErrorException.class)
            .when(sendContinueRequestHandler).prepareAndSendRequest(any());

        sendInboundMessageToQueue("/json/LargeMessage/Scenario_3/uk06.json");

        await().until(() -> hasMigrationStatus(EHR_GENERAL_PROCESSING_ERROR, getConversationId()));

        verify(sendContinueRequestHandler, times(1))
            .prepareAndSendRequest(any());

        assertThat(getCurrentMigrationStatus(getConversationId()))
            .isEqualTo(EHR_GENERAL_PROCESSING_ERROR);
    }

    @Test
    public void When_ReceivingCOPC_WithMhsOutboundServerError_Expect_MessageSentToDLQ() {
        doThrow(MhsServerErrorException.class)
            .when(sendACKMessageHandler).prepareAndSendMessage(any());

        doThrow(MhsServerErrorException.class)
            .when(sendNACKMessageHandler).prepareAndSendMessage(any());

        sendInboundMessageToQueue("/json/LargeMessage/Scenario_3/uk06.json");

        await().until(this::hasContinueMessageBeenReceived);

        sendInboundMessageToQueue("/json/LargeMessage/Scenario_3/copc.json");

        await().until(() -> hasMigrationStatus(COPC_FAILED, getConversationId()));

        verify(mhsDlqPublisher, timeout(THIRTY_SECONDS).times(1)).sendToMhsDlq(any());

        assertThat(getCurrentMigrationStatus(getConversationId()))
            .isEqualTo(COPC_FAILED);
    }

    @Test
    public void When_SendingInitialRequest_WithMhsWebClientRequestException_Expect_MigrationCompletesWhenMhsRecovers()
        throws JSONException {
        var conversationId = generateConversationId();
        var patientNhsNumber = generatePatientNhsNumber();

        doThrow(WebClientRequestException.class)
            .doThrow(WebClientRequestException.class)
            .doThrow(WebClientRequestException.class)
            .doThrow(WebClientRequestException.class)
            .doThrow(WebClientRequestException.class)
            .doCallRealMethod()
            .when(mhsClientService).send(any());

        sendRequestToPssQueue(conversationId, patientNhsNumber);

        await().atMost(Duration.ofMinutes(TWO_MINUTES_LONG))
            .until(() -> hasMigrationStatus(EHR_EXTRACT_REQUEST_ACCEPTED, conversationId));

        sendInboundMessageToQueue("/json/LargeMessage/Scenario_3/uk06.json");

        await().until(this::hasContinueMessageBeenReceived);

        sendInboundMessageToQueue("/json/LargeMessage/Scenario_3/copc.json");

        await().until(this::isEhrMigrationCompleted);

        verifyBundle("/json/LargeMessage/expectedBundleScenario3.json");
    }

    @Test
    public void When_ReceivingEhrExtract_WithMhsWebClientRequestException_Expect_MigrationCompletesWhenMhsRecovers() throws JSONException {
        doThrow(WebClientRequestException.class)
            .doThrow(WebClientRequestException.class)
            .doThrow(WebClientRequestException.class)
            .doThrow(WebClientRequestException.class)
            .doThrow(WebClientRequestException.class)
            .doCallRealMethod()
            .when(mhsClientService).send(any());

        sendInboundMessageToQueue("/json/LargeMessage/Scenario_3/uk06.json");

        await().atMost(Duration.ofMinutes(TWO_MINUTES_LONG))
            .until(this::hasContinueMessageBeenReceived);

        sendInboundMessageToQueue("/json/LargeMessage/Scenario_3/copc.json");

        await().until(this::isEhrMigrationCompleted);

        verifyBundle("/json/LargeMessage/expectedBundleScenario3.json");
    }

    @Test
    public void When_ReceivingCopc_WithMhsWebClientRequestException_Expect_MigrationCompletesWhenMhsRecovers() throws JSONException {

        sendInboundMessageToQueue("/json/LargeMessage/Scenario_3/uk06.json");

        await().until(this::hasContinueMessageBeenReceived);

        doThrow(WebClientRequestException.class)
            .doThrow(WebClientRequestException.class)
            .doThrow(WebClientRequestException.class)
            .doThrow(WebClientRequestException.class)
            .doThrow(WebClientRequestException.class)
            .doCallRealMethod()
            .when(mhsClientService).send(any());

        sendInboundMessageToQueue("/json/LargeMessage/Scenario_3/copc.json");

        await().atMost(Duration.ofMinutes(TWO_MINUTES_LONG))
            .until(this::isEhrMigrationCompleted);

        verifyBundle("/json/LargeMessage/expectedBundleScenario3.json");
    }

    @Test
    public void When_SendingInitialRequest_WithDBConnectionException_Expect_MigrationCompletesWhenMhsRecovers() throws JSONException {
        var conversationId = generateConversationId();
        var patientNhsNumber = generatePatientNhsNumber();

        doThrow(ConnectionException.class)
            .doThrow(ConnectionException.class)
            .doThrow(ConnectionException.class)
            .doThrow(ConnectionException.class)
            .doThrow(ConnectionException.class)
            .doCallRealMethod()
            .when(mhsClientService).send(any());

        sendRequestToPssQueue(conversationId, patientNhsNumber);

        await().atMost(Duration.ofMinutes(TWO_MINUTES_LONG))
            .until(() -> hasMigrationStatus(EHR_EXTRACT_REQUEST_ACCEPTED, conversationId));

        sendInboundMessageToQueue("/json/LargeMessage/Scenario_3/uk06.json");

        await().until(this::hasContinueMessageBeenReceived);

        sendInboundMessageToQueue("/json/LargeMessage/Scenario_3/copc.json");

        await().until(this::isEhrMigrationCompleted);

        verifyBundle("/json/LargeMessage/expectedBundleScenario3.json");
    }

    @Test
    public void When_ReceivingEhrExtract_WithDbConnectionException_Expect_MigrationCompletesWhenMhsRecovers() throws JSONException {
        doThrow(ConnectionException.class)
            .doThrow(ConnectionException.class)
            .doThrow(ConnectionException.class)
            .doThrow(ConnectionException.class)
            .doThrow(ConnectionException.class)
            .doCallRealMethod()
            .when(mhsClientService).send(any());

        sendInboundMessageToQueue("/json/LargeMessage/Scenario_3/uk06.json");

        await().atMost(Duration.ofMinutes(TWO_MINUTES_LONG))
            .until(this::hasContinueMessageBeenReceived);

        sendInboundMessageToQueue("/json/LargeMessage/Scenario_3/copc.json");

        await().until(this::isEhrMigrationCompleted);

        verifyBundle("/json/LargeMessage/expectedBundleScenario3.json");
    }

    @Test
    public void When_ReceivingCopc_WithDbConnectionException_Expect_MigrationCompletesWhenMhsRecovers() throws JSONException {

        sendInboundMessageToQueue("/json/LargeMessage/Scenario_3/uk06.json");

        await().until(this::hasContinueMessageBeenReceived);

        doThrow(ConnectionException.class)
            .doThrow(ConnectionException.class)
            .doThrow(ConnectionException.class)
            .doThrow(ConnectionException.class)
            .doThrow(ConnectionException.class)
            .doCallRealMethod()
            .when(mhsClientService).send(any());

        sendInboundMessageToQueue("/json/LargeMessage/Scenario_3/copc.json");

        await().atMost(Duration.ofMinutes(TWO_MINUTES_LONG))
            .until(this::isEhrMigrationCompleted);

        verifyBundle("/json/LargeMessage/expectedBundleScenario3.json");
    }

    private boolean hasMigrationStatus(MigrationStatus migrationStatus, String conversationId) {
        return migrationStatus.equals(getCurrentMigrationStatus(conversationId));
    }

    private MigrationStatus getCurrentMigrationStatus(String conversationId) {
        return getMigrationStatusLogService().getLatestMigrationStatusLog(conversationId).getMigrationStatus();
    }

    private void sendRequestToPssQueue(String conversationId, String patientNhsNumber) {

        getPatientMigrationRequestDao()
            .addNewRequest(patientNhsNumber, conversationId, getLosingODSCode(), getWiningODSCode());
        getMigrationStatusLogService()
            .addMigrationStatusLog(REQUEST_RECEIVED, conversationId, null);

        var transferRequestMessage = TransferRequestMessage.builder()
            .patientNhsNumber(patientNhsNumber)
            .toOds(getLosingODSCode())
            .fromOds(getWiningODSCode())
            .fromAsid(WINNING_ASID)
            .toAsid(LOSING_ASID)
            .messageType(TRANSFER_REQUEST)
            .conversationId(conversationId)
            .build();

        getPssJmsTemplate().send(session ->
            session.createTextMessage(getObjectAsString(transferRequestMessage)));
    }

    @SneakyThrows
    private String getObjectAsString(Object object) {
        return objectMapper.writeValueAsString(object);
    }

    private WebClientResponseException getInternalServerErrorException() {
        return new WebClientResponseException(
            INTERNAL_SERVER_ERROR.value(), INTERNAL_SERVER_ERROR.getReasonPhrase(), httpHeaders, STUB_BODY.getBytes(UTF_8), UTF_8);
    }

    private void sendInboundMessageToQueue(String json) {
        var jsonMessage = readResourceAsString(json)
            .replace(NHS_NUMBER_PLACEHOLDER, getPatientNhsNumber())
            .replace(CONVERSATION_ID_PLACEHOLDER, getConversationId());
        getMhsJmsTemplate().send(session -> session.createTextMessage(jsonMessage));
    }

    private boolean hasContinueMessageBeenReceived() {
        var migrationStatusLog = getMigrationStatusLogService().getLatestMigrationStatusLog(getConversationId());
        return CONTINUE_REQUEST_ACCEPTED.equals(migrationStatusLog.getMigrationStatus());
    }
}