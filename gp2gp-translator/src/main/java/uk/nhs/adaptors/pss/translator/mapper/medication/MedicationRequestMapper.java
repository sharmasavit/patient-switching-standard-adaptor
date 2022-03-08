package uk.nhs.adaptors.pss.translator.mapper.medication;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import org.hl7.fhir.dstu3.model.DateTimeType;
import org.hl7.fhir.dstu3.model.DomainResource;
import org.hl7.fhir.dstu3.model.Encounter;
import org.hl7.fhir.dstu3.model.IdType;
import org.hl7.fhir.dstu3.model.Medication;
import org.hl7.fhir.dstu3.model.MedicationRequest;
import org.hl7.fhir.dstu3.model.MedicationStatement;
import org.hl7.fhir.dstu3.model.Patient;
import org.hl7.fhir.dstu3.model.Reference;
import org.hl7.fhir.dstu3.model.ResourceType;
import org.hl7.v3.RCMRMT030101UK04Component;
import org.hl7.v3.RCMRMT030101UK04Component02;
import org.hl7.v3.RCMRMT030101UK04Component2;
import org.hl7.v3.RCMRMT030101UK04Component3;
import org.hl7.v3.RCMRMT030101UK04Component4;
import org.hl7.v3.RCMRMT030101UK04EhrComposition;
import org.hl7.v3.RCMRMT030101UK04EhrExtract;
import org.hl7.v3.RCMRMT030101UK04EhrFolder;
import org.hl7.v3.RCMRMT030101UK04MedicationStatement;
import org.springframework.stereotype.Service;

import lombok.AllArgsConstructor;

import uk.nhs.adaptors.pss.translator.util.CompoundStatementUtil;
import uk.nhs.adaptors.pss.translator.util.DateFormatUtil;

@Service
@AllArgsConstructor
public class MedicationRequestMapper {
    private MedicationMapper medicationMapper;
    private MedicationRequestOrderMapper medicationRequestOrderMapper;
    private MedicationRequestPlanMapper medicationRequestPlanMapper;
    private MedicationStatementMapper medicationStatementMapper;

    private static final String PPRF = "PPRF";
    private static final String PRF = "PRF";

    public List<DomainResource> mapResources(RCMRMT030101UK04EhrExtract ehrExtract, List<Encounter> encounters, Patient patient,
        String practiseCode) {
        List<DomainResource> mappedResources = new ArrayList<>();
        List<RCMRMT030101UK04EhrComposition> ehrCompositions = ehrExtract.getComponent()
            .stream()
            .map(RCMRMT030101UK04Component::getEhrFolder)
            .map(RCMRMT030101UK04EhrFolder::getComponent)
            .flatMap(List::stream)
            .map(RCMRMT030101UK04Component3::getEhrComposition)
            .toList();

        for (RCMRMT030101UK04EhrComposition ehrComposition : ehrCompositions) {
            var context = encounters.stream()
                .filter(encounter1 -> encounter1.getId().equals(ehrComposition.getId().getRoot())).findFirst();

            ehrComposition.getComponent()
                .stream()
                .flatMap(this::extractAllMedications)
                .filter(Objects::nonNull)
                .map(medicationStatement
                    -> mapMedicationStatement(ehrExtract, ehrComposition, medicationStatement, patient, context, practiseCode))
                .flatMap(List::stream)
                .forEach(mappedResources::add);
        }
        return mappedResources;
    }

    private Stream<RCMRMT030101UK04MedicationStatement> extractAllMedications(RCMRMT030101UK04Component4 component4) {
        return Stream.concat(
            Stream.of(component4.getMedicationStatement()),
            component4.hasCompoundStatement()
                ? CompoundStatementUtil.extractResourcesFromCompound(component4.getCompoundStatement(),
                RCMRMT030101UK04Component02::hasMedicationStatement, RCMRMT030101UK04Component02::getMedicationStatement)
                .stream()
                .map(RCMRMT030101UK04MedicationStatement.class::cast)
                : Stream.empty()
        );
    }

    private List<DomainResource> mapMedicationStatement(RCMRMT030101UK04EhrExtract ehrExtract,
        RCMRMT030101UK04EhrComposition ehrComposition, RCMRMT030101UK04MedicationStatement medicationStatement,
        Patient subject, Optional<Encounter> context, String practiseCode) {

        var authoredOn = extractAuthoredOn(ehrComposition,
            DateFormatUtil.parseToDateTimeType(ehrExtract.getAvailabilityTime().getValue()));
        var requester = extractRequester(ehrComposition, medicationStatement);
        var recorder = extractRecorder(ehrComposition, medicationStatement);

        List<Medication> medications = mapMedications(medicationStatement);

        List<MedicationRequest> medicationRequestsOrder = mapMedicationRequestsOrder(medicationStatement, practiseCode);

        List<MedicationRequest> medicationRequestsPlan = mapMedicationRequestsPlan(ehrExtract, medicationStatement, practiseCode);

        List<MedicationStatement> medicationStatements = mapMedicationStatements(medicationStatement, context, subject, authoredOn,
            practiseCode);

        return Stream.of(medications, medicationRequestsOrder, medicationRequestsPlan, medicationStatements)
            .flatMap(List::stream)
            .map(DomainResource.class::cast)
            .map(medicationRequest -> setCommonFields(
                medicationRequest, requester, recorder, subject, context, authoredOn))
            .toList();
    }

    private DomainResource setCommonFields(DomainResource resource, Optional<Reference> requester,
        Optional<Reference> recorder, Patient patient, Optional<Encounter> context, DateTimeType authoredOn) {

        if (ResourceType.MedicationRequest.equals(resource.getResourceType())) {
            ((MedicationRequest) resource).setSubject(new Reference(patient));
            context.ifPresent(context1 -> ((MedicationRequest) resource).setContext(new Reference(context1)));

            requester.map(MedicationRequest.MedicationRequestRequesterComponent::new)
                .ifPresent(((MedicationRequest) resource)::setRequester);
            recorder.ifPresent(((MedicationRequest) resource)::setRecorder);
            ((MedicationRequest) resource).setAuthoredOnElement(authoredOn);
        }

        return resource;
    }

    private List<Medication> mapMedications(RCMRMT030101UK04MedicationStatement medicationStatement) {
        return medicationStatement.getConsumable()
            .stream()
            .map(medicationMapper::createMedication)
            .toList();
    }

    private List<MedicationRequest> mapMedicationRequestsOrder(RCMRMT030101UK04MedicationStatement medicationStatement,
        String practiseCode) {
        return medicationStatement.getComponent()
            .stream()
            .filter(RCMRMT030101UK04Component2::hasEhrSupplyPrescribe)
            .map(RCMRMT030101UK04Component2::getEhrSupplyPrescribe)
            .map(supplyPrescribe -> medicationRequestOrderMapper.mapToOrderMedicationRequest(medicationStatement, supplyPrescribe,
                practiseCode))
            .filter(Objects::nonNull)
            .toList();
    }

    private List<MedicationRequest> mapMedicationRequestsPlan(RCMRMT030101UK04EhrExtract ehrExtract,
        RCMRMT030101UK04MedicationStatement medicationStatement, String practiseCode) {
        return medicationStatement.getComponent()
            .stream()
            .filter(RCMRMT030101UK04Component2::hasEhrSupplyAuthorise)
            .map(RCMRMT030101UK04Component2::getEhrSupplyAuthorise)
            .map(supplyAuthorise -> medicationRequestPlanMapper.mapToPlanMedicationRequest(ehrExtract, medicationStatement,
                supplyAuthorise, practiseCode))
            .filter(Objects::nonNull)
            .toList();
    }

    private List<MedicationStatement> mapMedicationStatements(RCMRMT030101UK04MedicationStatement medicationStatement,
        Optional<Encounter> context, Patient subject, DateTimeType authoredOn, String practiseCode) {
        return medicationStatement.getComponent()
            .stream()
            .filter(RCMRMT030101UK04Component2::hasEhrSupplyAuthorise)
            .map(RCMRMT030101UK04Component2::getEhrSupplyAuthorise)
            .map(supplyAuthorise -> medicationStatementMapper.mapToMedicationStatement(medicationStatement, supplyAuthorise, practiseCode))
            .filter(Objects::nonNull)
            .peek(medicationStatement1 -> {
                context.ifPresent(context1 -> medicationStatement1.setContext(new Reference(context1)));
                medicationStatement1.setSubject(new Reference(subject));
                medicationStatement1.setEffective(authoredOn);
                medicationStatement1.setDateAssertedElement(authoredOn);
            })
            .toList();
    }

    private DateTimeType extractAuthoredOn(RCMRMT030101UK04EhrComposition ehrComposition, DateTimeType ehrExtractAvailabilityTime) {
        if (ehrComposition.hasAuthor() && ehrComposition.getAuthor().hasTime() && ehrComposition.getAuthor().getTime().hasValue()) {
            return DateFormatUtil.parseToDateTimeType(ehrComposition.getAuthor().getTime().getValue());
        } else {
            return ehrExtractAvailabilityTime;
        }
    }

    private Optional<Reference> extractRequester(RCMRMT030101UK04EhrComposition ehrComposition,
        RCMRMT030101UK04MedicationStatement medicationStatement) {
        if (medicationStatement.hasParticipant()) {
            var pprfRequester = medicationStatement.getParticipant()
                .stream()
                .filter(participant -> !participant.hasNullFlavour())
                .filter(participant -> participant.getTypeCode().contains(PPRF) || participant.getTypeCode().contains(PRF))
                .findFirst();
            if (pprfRequester.isPresent()) {
                return pprfRequester
                    .map(requester -> new IdType(ResourceType.Practitioner.name(), requester.getAgentRef().getId().getRoot()))
                    .map(Reference::new);
            }
        }

        if (ehrComposition.hasParticipant2()) {
            var requester = ehrComposition.getParticipant2()
                .stream()
                .filter(participant -> !participant.hasNullFlavor())
                .findFirst();

            if (requester.isPresent()) {
                return requester
                    .map(requester1 -> new IdType(ResourceType.Practitioner.name(), requester1.getAgentRef().getId().getRoot()))
                    .map(Reference::new);
            }
        }
        return Optional.empty();
    }

    private Optional<Reference> extractRecorder(RCMRMT030101UK04EhrComposition ehrComposition,
        RCMRMT030101UK04MedicationStatement medicationStatement) {
        return extractRequester(ehrComposition, medicationStatement);
    }
}