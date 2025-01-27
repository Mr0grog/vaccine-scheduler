package gov.usds.vaccineschedule.api.pagination;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jaxrs.server.AbstractJaxRsResourceProvider;
import ca.uhn.fhir.rest.annotation.ResourceParam;
import ca.uhn.fhir.rest.annotation.Validate;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ValidationOptions;
import ca.uhn.fhir.validation.ValidationResult;
import gov.usds.vaccineschedule.api.helpers.BaseURLProvider;
import gov.usds.vaccineschedule.api.models.BundleFactory;
import gov.usds.vaccineschedule.api.models.OffsetLinkBuilder;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.InstantType;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Created by nickrobison on 4/5/21
 */
public abstract class AbstractPaginatingAndValidatingProvider<T extends IBaseResource> extends AbstractJaxRsResourceProvider<T> {

    private static final int MAX_PAGE_SIZE = 500;
    private final BaseURLProvider provider;
    private final FhirValidator validator;

    public AbstractPaginatingAndValidatingProvider(FhirContext ctx, FhirValidator validator, BaseURLProvider provider) {
        super(ctx);
        this.provider = provider;
        this.validator = validator;
    }

    /**
     * Generate a {@link PageRequest} which can be passed to the JPA backend
     * HAPI ensures that the {@link Integer} values can never be negative, but we're responsible for ensuring they aren't insane.
     * Eventually we'll need to pull these values from configuration, but for now, we default to a page size of 50 with a maximum page size of {@link AbstractPaginatingAndValidatingProvider#MAX_PAGE_SIZE}
     *
     * @param pageOffset - {@link Integer} optional value for page offset. If empty, 0 is used
     * @param pageSize   - {@link Integer} optional value for requested page size. If empty, 50 is used
     * @return - {@link Pageable} to pass to JPA
     */
    public Pageable buildPageRequest(@Nullable Integer pageOffset, @Nullable Integer pageSize) {
        // If we have a null offset, then we return the first page
        final int page = pageOffset == null ? 0 : pageOffset;
        // This will need to be pull from the configuration
        final int size = pageSize == null ? 50 : pageSize;

        if (size > MAX_PAGE_SIZE) {
            throw new InvalidRequestException(String.format("Page size of: %d exceeds the maximum of %d", pageSize, 500));
        }
        return PageRequest.of(page, size);
    }

    public Bundle createBundle(RequestDetails details, List<T> resources, InstantType searchTime, Pageable page, long totalCount) {
        final String baseUrl = provider.get();
        final OffsetLinkBuilder builder = new OffsetLinkBuilder(baseUrl, details, getResourceType().getSimpleName(), page.getPageSize(), page.getPageNumber(), totalCount);
        return BundleFactory.createBundle(baseUrl, resources, builder, searchTime, totalCount);
    }

    @Validate
    public MethodOutcome validateResource(@ResourceParam T resource) {
        return doValidation(resource, getResourceProfile());
    }


    protected MethodOutcome doValidation(T resource, String profile) {
        final ValidationOptions options = new ValidationOptions();
        options.addProfile(profile);
        final MethodOutcome outcome = new MethodOutcome();
        final ValidationResult result = this.validator.validateWithResult(resource, options);

        if (!result.isSuccessful()) {
            throw new UnprocessableEntityException("Validation failed", result.toOperationOutcome());
        }

        // If we have some additional diagnostic messages, we could add them here
        return outcome;
    }

    public abstract String getResourceProfile();
}
