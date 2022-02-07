package uk.nhs.adaptors.pss.gpc.service;

import static uk.nhs.adaptors.connector.model.MigrationStatus.REQUEST_RECEIVED;

import org.hl7.fhir.dstu3.model.Bundle;
import org.hl7.fhir.dstu3.model.Parameters;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import uk.nhs.adaptors.common.util.DateUtils;
import uk.nhs.adaptors.common.util.fhir.FhirParser;
import uk.nhs.adaptors.common.util.fhir.ParametersUtils;
import uk.nhs.adaptors.connector.dao.MigrationStatusLogDao;
import uk.nhs.adaptors.connector.dao.PatientMigrationRequestDao;
import uk.nhs.adaptors.connector.model.MigrationStatusLog;
import uk.nhs.adaptors.connector.model.PatientMigrationRequest;
import uk.nhs.adaptors.pss.gpc.amqp.PssQueuePublisher;

@Component
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class PatientTransferService {
    private final FhirParser fhirParser;
    private final PatientMigrationRequestDao patientMigrationRequestDao;
    private final MigrationStatusLogDao migrationStatusLogDao;
    private final PssQueuePublisher pssQueuePublisher;
    private final DateUtils dateUtils;

    public MigrationStatusLog handlePatientMigrationRequest(Parameters parameters) {
        var patientNhsNumber = ParametersUtils.getNhsNumberFromParameters(parameters).get().getValue();
        PatientMigrationRequest patientMigrationRequest = patientMigrationRequestDao.getMigrationRequest(patientNhsNumber);

        if (patientMigrationRequest == null) {
            pssQueuePublisher.sendToPssQueue(fhirParser.encodeToJson(parameters));
            patientMigrationRequestDao.addNewRequest(patientNhsNumber);

            int addedId = patientMigrationRequestDao.getMigrationRequestId(patientNhsNumber);
            migrationStatusLogDao.addMigrationStatusLog(REQUEST_RECEIVED, dateUtils.getCurrentOffsetDateTime(), addedId);
        } else {
            return migrationStatusLogDao.getMigrationStatusLog(patientMigrationRequest.getId());
        }
        return null;
    }

    public String getEmptyBundle() {
        return fhirParser.encodeToJson(new Bundle());
    }
}