package uk.nhs.adaptors.pss.translator.mapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.hl7.fhir.dstu3.model.Annotation;
import org.hl7.fhir.dstu3.model.Encounter;
import org.hl7.fhir.dstu3.model.Extension;
import org.hl7.fhir.dstu3.model.Identifier;
import org.hl7.fhir.dstu3.model.Immunization;
import org.hl7.fhir.dstu3.model.Immunization.ImmunizationPractitionerComponent;
import org.hl7.fhir.dstu3.model.Immunization.ImmunizationStatus;
import org.hl7.fhir.dstu3.model.Patient;
import org.hl7.fhir.dstu3.model.Reference;
import org.hl7.fhir.dstu3.model.StringType;
import org.hl7.v3.II;
import org.hl7.v3.RCMRMT030101UK04Annotation;
import org.hl7.v3.RCMRMT030101UK04Component4;
import org.hl7.v3.RCMRMT030101UK04EhrComposition;
import org.hl7.v3.RCMRMT030101UK04EhrExtract;
import org.hl7.v3.RCMRMT030101UK04ObservationStatement;
import org.hl7.v3.RCMRMT030101UK04PertinentInformation02;
import org.springframework.stereotype.Service;

import lombok.AllArgsConstructor;
import uk.nhs.adaptors.pss.translator.util.DateFormatUtil;
import uk.nhs.adaptors.pss.translator.util.EhrResourceExtractorUtil;
import uk.nhs.adaptors.pss.translator.util.ParticipantReferenceUtil;

@Service
@AllArgsConstructor
public class ImmunizationMapper {
    private static final String META_PROFILE = "https://fhir.nhs.uk/STU3/StructureDefinition/CareConnect-GPC-Immunization-1";
    private static final String IMMUNIZATION_SNOMED_CODE = "2.16.840.1.113883.2.1.3.2.3.15";
    private static final String IDENTIFIER_SYSTEM = "https://PSSAdaptor/";
    private static final String VACCINE_PROCEDURE_URL = "https://fhir.hl7.org.uk/STU3/StructureDefinition/Extension-CareConnect"
        + "-VaccinationProcedure-1";
    private static final String END_DATE_PREFIX = "End Date: ";
    private static final String VACCINATION_CODING_EXTENSION_URL = "https://fhir.nhs.uk/STU3/StructureDefinition/Extension-coding"
        + "-sctdescid";
    private static final String RECORDED_DATE_EXTENSION_URL = "https://fhir.hl7.org.uk/STU3/StructureDefinition/Extension-CareConnect"
        + "-DateRecorded-1";

    private CodeableConceptMapper codeableConceptMapper;

    public List<Immunization> mapToImmunization(RCMRMT030101UK04EhrExtract ehrExtract, Patient patientResource,
        List<Encounter> encounterList) {
        List<Immunization> mappedImmunizationResources = new ArrayList<>();
        var ehrCompositionList = EhrResourceExtractorUtil.extractValidImmunizationEhrCompositions(ehrExtract);

        ehrCompositionList.forEach(ehrComposition -> {
            var immunizationObservationStatements = getImmunizationObservationStatements(ehrComposition);

            immunizationObservationStatements
                .forEach(observationStatement -> {
                    var mappedImmunization = mapImmunization(ehrComposition, observationStatement, patientResource,
                        encounterList);
                    mappedImmunizationResources.add(mappedImmunization);
                });
        });

        return mappedImmunizationResources;
    }

    private Immunization mapImmunization(RCMRMT030101UK04EhrComposition ehrComposition,
        RCMRMT030101UK04ObservationStatement observationStatement, Patient patientResource, List<Encounter> encounterList) {
        Immunization immunization = new Immunization();

        var id = observationStatement.getId().getRoot();
        var identifier = getIdentifier(id);

        var practitioner = ParticipantReferenceUtil.getParticipantReference(observationStatement.getParticipant(), ehrComposition);
        var encounter = getEncounterReference(encounterList, ehrComposition.getId());

        immunization.getMeta().addProfile(META_PROFILE);
        immunization.addIdentifier(identifier);
        immunization.addExtension(createVaccineProcedureExtension(observationStatement));
        immunization.addExtension(createRecordedTimeExtension(ehrComposition));
        immunization
            .setEncounter(encounter)
            .addPractitioner(new ImmunizationPractitionerComponent(practitioner))
            .setStatus(ImmunizationStatus.COMPLETED)
            .setNotGiven(false)
            .setPrimarySource(false)
            .setPatient(new Reference(patientResource))
            .setId(id);
        buildNote(observationStatement).forEach(immunization::addNote);
        setDateFields(immunization, observationStatement);

        return immunization;
    }

    private Reference getEncounterReference(List<Encounter> encounterList, II ehrCompositionId) {
        if (ehrCompositionId != null) {
            var matchingEncounter = encounterList.stream()
                .filter(encounter -> hasMatchingId(encounter.getId(), ehrCompositionId))
                .findFirst();

            if (matchingEncounter.isPresent()) {
                return new Reference(matchingEncounter.get());
            }
        }

        return null;
    }

    private boolean hasMatchingId(String encounterId, II ehrCompositionId) {
        return encounterId.equals(ehrCompositionId.getRoot());
    }

    private List<RCMRMT030101UK04ObservationStatement> getImmunizationObservationStatements(RCMRMT030101UK04EhrComposition ehrComposition) {
        return ehrComposition.getComponent()
            .stream()
            .map(RCMRMT030101UK04Component4::getObservationStatement)
            .filter(Objects::nonNull)
            .filter(this::hasImmunizationCode)
            .collect(Collectors.toList());
    }

    private Extension createRecordedTimeExtension(RCMRMT030101UK04EhrComposition ehrComposition) {
        var extension = new Extension();
        extension.setUrl(RECORDED_DATE_EXTENSION_URL);

        if (ehrComposition.getAuthor() != null) {
            return extension
                .setValue(new StringType(ehrComposition.getAuthor().getTime().getValue()));
        } else if (ehrComposition.getEffectiveTime() != null && ehrComposition.getAvailabilityTime().getNullFlavor() == null) {
            return extension
                .setValue(new StringType(
                    DateFormatUtil.parseToDateTimeType(ehrComposition.getAvailabilityTime().getValue()).asStringValue()));
        }

        return null;
    }

    private Identifier getIdentifier(String id) {
        return new Identifier()
            .setSystem(IDENTIFIER_SYSTEM) // TODO: concatenate source practice org id to URL (NIAD-2021)
            .setValue(id);
    }

    private boolean hasImmunizationCode(RCMRMT030101UK04ObservationStatement observationStatement) {
        String snomedCode = observationStatement.getCode().getCodeSystem();

        return IMMUNIZATION_SNOMED_CODE.equals(snomedCode);
    }

    private Extension createVaccineProcedureExtension(RCMRMT030101UK04ObservationStatement observationStatement) {
        return new Extension()
            .setUrl(VACCINE_PROCEDURE_URL)
            .setValue(new Extension()
                .setUrl(VACCINATION_CODING_EXTENSION_URL)
                .setValue(codeableConceptMapper.mapToCodeableConcept(observationStatement.getCode()))
            );
    }

    private Annotation buildAnnotation(String annotation) {
        return new Annotation(new StringType(annotation));
    }

    private void setDateFields(Immunization immunization, RCMRMT030101UK04ObservationStatement observationStatement) {
        if (observationStatement.hasEffectiveTime()) {
            var effectiveTime = observationStatement.getEffectiveTime();

            if (effectiveTime.hasHigh() && !effectiveTime.hasCenter()) {
                immunization.addNote(buildAnnotation(END_DATE_PREFIX
                    + DateFormatUtil.parseToDateTimeType(effectiveTime.getHigh().getValue())));
            }

            if (effectiveTime.hasCenter()) {
                immunization.setDateElement(DateFormatUtil.parseToDateTimeType(effectiveTime.getCenter().getValue()));
            } else if (effectiveTime.hasLow()) {
                immunization.setDateElement(DateFormatUtil.parseToDateTimeType(effectiveTime.getLow().getValue()));
            } else if (observationStatement.hasAvailabilityTime() && observationStatement.getAvailabilityTime().hasValue()) {
                immunization.setDateElement(DateFormatUtil.parseToDateTimeType(observationStatement.getAvailabilityTime().getValue()));
            }
        }
    }

    private List<Annotation> buildNote(RCMRMT030101UK04ObservationStatement observationStatement) {
        return observationStatement
            .getPertinentInformation()
            .stream()
            .map(RCMRMT030101UK04PertinentInformation02::getPertinentAnnotation)
            .map(RCMRMT030101UK04Annotation::getText)
            .map(this::buildAnnotation)
            .collect(Collectors.toList());
    }
}