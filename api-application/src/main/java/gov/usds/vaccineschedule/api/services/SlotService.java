package gov.usds.vaccineschedule.api.services;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.param.DateRangeParam;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ValidationFailureException;
import ca.uhn.fhir.validation.ValidationOptions;
import ca.uhn.fhir.validation.ValidationResult;
import gov.usds.vaccineschedule.api.db.models.ScheduleEntity;
import gov.usds.vaccineschedule.api.db.models.SlotEntity;
import gov.usds.vaccineschedule.api.repositories.ScheduleRepository;
import gov.usds.vaccineschedule.api.repositories.SlotRepository;
import gov.usds.vaccineschedule.common.models.VaccineSlot;
import org.hl7.fhir.r4.model.Slot;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static gov.usds.vaccineschedule.api.repositories.SlotRepository.forLocation;
import static gov.usds.vaccineschedule.api.repositories.SlotRepository.forLocationAndTime;
import static gov.usds.vaccineschedule.api.repositories.SlotRepository.withIdentifier;
import static gov.usds.vaccineschedule.common.Constants.ORIGINAL_ID_SYSTEM;

/**
 * Created by nickrobison on 3/29/21
 */
@Service
@Transactional(readOnly = true)
public class SlotService {

    private final ScheduleRepository scheduleRepository;
    private final SlotRepository repo;
    private final FhirContext ctx;
    private final FhirValidator validator;


    public SlotService(ScheduleRepository scheduleRepository, SlotRepository repo, FhirContext ctx, FhirValidator validator) {
        this.scheduleRepository = scheduleRepository;
        this.repo = repo;
        this.ctx = ctx;
        this.validator = validator;
    }


    public long countSlotsWithId(TokenParam identifier) {
        return this.repo.count(withIdentifier(identifier.getSystem(), identifier.getValue()));
    }

    public List<Slot> findSlotsWithId(TokenParam identifier, Pageable pageable) {
        return this.repo.findAll(withIdentifier(identifier.getSystem(), identifier.getValue()), pageable)
                .stream().map(SlotEntity::toFHIR)
                .collect(Collectors.toList());
    }

    public List<Slot> getSlots(Pageable pageable) {
        return StreamSupport
                .stream(this.repo.findAll(pageable).spliterator(), false)
                .map(SlotEntity::toFHIR)
                .collect(Collectors.toList());
    }

    public long countSlots() {
        return this.repo.count();
    }

    public long countSlotsForLocation(ReferenceParam idParam, @Nullable DateRangeParam dateParam) {
        final Specification<SlotEntity> query = buildLocationSearchQuery(idParam, dateParam);
        return this.repo.count(query);
    }

    public List<Slot> getSlotsForLocation(ReferenceParam idParam, @Nullable DateRangeParam dateParam, Pageable pageable) {
        final Specification<SlotEntity> searchParams = buildLocationSearchQuery(idParam, dateParam);

        return this.repo.findAll(searchParams, pageable)
                .stream()
                .map(SlotEntity::toFHIR)
                .collect(Collectors.toList());
    }

    @Transactional
    public Collection<Slot> addSlots(Collection<VaccineSlot> resources) {
        return resources
                .stream().map(this::addSlot)
                .collect(Collectors.toList());
    }

    @Transactional
    public Slot addSlot(VaccineSlot resource) {
        this.validateSlot(resource);
        final String scheduleRef = resource.getSchedule().getReference();

        final List<ScheduleEntity> schedule = new ArrayList<>(scheduleRepository.findAll(ScheduleRepository.hasIdentifier(ORIGINAL_ID_SYSTEM, scheduleRef)));
        if (schedule.isEmpty()) {
            throw new IllegalStateException("Cannot add to missing schedule");
        }

        final SlotEntity entity = SlotEntity.fromFHIR(schedule.get(0), resource);

        final Optional<SlotEntity> maybeExists = this.repo.findOne(withIdentifier(ORIGINAL_ID_SYSTEM, resource.getId()));
        if (maybeExists.isPresent()) {
            // Merge
            final SlotEntity existing = maybeExists.get();
            existing.merge(entity);
            return repo.save(existing).toFHIR();
        } else {
            return repo.save(entity).toFHIR();
        }
    }

    private static Specification<SlotEntity> buildLocationSearchQuery(ReferenceParam idParam, DateRangeParam dateParam) {
        final UUID id = UUID.fromString(idParam.getIdPart());

        final Specification<SlotEntity> searchParams;
        if (dateParam == null) {
            searchParams = forLocation(id);
        } else {
            searchParams = forLocationAndTime(id, dateParam);
        }
        return searchParams;
    }

    private void validateSlot(VaccineSlot slot) {
        final ValidationOptions options = new ValidationOptions();
        options.addProfile("http://fhir-registry.smarthealthit.org/StructureDefinition/vaccine-slot");

        final ValidationResult result = this.validator.validateWithResult(slot, options);
        if (!result.isSuccessful()) {
            throw new ValidationFailureException(this.ctx, result.toOperationOutcome());
        }
    }
}
