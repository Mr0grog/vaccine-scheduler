package gov.usds.vaccineschedule.api.services;

import ca.uhn.fhir.context.FhirContext;
import cov.usds.vaccineschedule.common.models.PublishResponse;
import gov.usds.vaccineschedule.api.config.ScheduleSourceConfig;
import gov.usds.vaccineschedule.common.helpers.NDJSONToFHIR;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Created by nickrobison on 3/25/21
 */
@Service
public class SourceFetchService {
    private static final Logger logger = LoggerFactory.getLogger(SourceFetchService.class);

    private final ScheduleSourceConfig config;
    private final NDJSONToFHIR converter;
    private final Sinks.Many<String> processor;

    private Disposable disposable;

    public SourceFetchService(FhirContext context, ScheduleSourceConfig config) {
        this.config = config;
        this.converter = new NDJSONToFHIR(context.newJsonParser());
        this.processor = Sinks.many().unicast().onBackpressureBuffer();
    }

    @Bean
    public Supplier<Flux<String>> supplyStream() {
        return () -> this.processor.asFlux().log();
    }

    @Bean
    public Consumer<Flux<String>> receiveStream() {
        return (stream) -> stream.log().subscribe(this::handleRefresh);
    }

    /**
     * Submit the sources to the job queue for processing, asynchronously by the workers
     */
    public void refreshSources() {
        this.config.getSources().forEach(source -> this.processor.emitNext(source, Sinks.EmitFailureHandler.FAIL_FAST));
    }

    private void handleRefresh(String source) {
        final WebClient client = WebClient.create(source);
        client.get()
                .uri("/$bulk-publish")
                .retrieve()
                .bodyToMono(PublishResponse.class)
                .flatMapMany(response -> Flux.fromIterable(response.getOutput())
                        .map(PublishResponse.OutputEntry::getUrl)
                        .flatMap(url -> client.get().uri("data" + url).retrieve().bodyToMono(DataBuffer.class))
                        .flatMap(body -> Flux.fromIterable(converter.inputStreamToResource(body.asInputStream(true)))))
                .onErrorContinue((error) -> error instanceof WebClientException,
                        (throwable, o) -> { // If we throw an exception
                            logger.error("Cannot process resource: {}", o, throwable);
                        })
                .subscribe(resource -> logger.info("Received resource: {}", resource), (error) -> {
                    throw new RuntimeException(error);
                });
    }
}
