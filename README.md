# Vaccine Scheduler

This is a prototype implementation of the [SMART Scheduling Links](https://github.com/smart-on-fhir/smart-scheduling-links/blob/master/specification.md#deep-links-hosted-by-provider-booking-portal)
API.

## Theory of operation

This repo contains two services, a `publisher` and an `api`.

The `publisher` implements the `$bulk-publish` FHIR operation and exposes some test locations, schedules and slots as
FHIR bundles.

The `api` regularly polls the publisher and loads the data into the repository.

Users can access the `api` and search for available slots at given locations.

## Refreshing the data.

The API service sets up some cron jobs to regularly poll a list of upstream publishers for their data. The cron jobs
don't actually fetch the data, instead, they submit jobs to a `RabbitMQ` queue, which is subscribed to by the various
API services. When a job is received the attached URL is polled (by calling the `$bulk-publish` endpoint) and the listed
files are downloaded and processed.

Given the hierarchical nature of the data, we can process upstreams in parallel, but we have to process the resource
groups sequentially.
(e.g. load all the locations before we load the schedules)

## Running:

```bash
docker-compose up --build
```

### Ports:

- API: 8080
- Publisher: 9090
- Postgres: 5432
- RabbitMQ: 5672

## Run the example

By default, the application loads a handful of locations and slots, pulled from the specification repo. These locations
exist in MA for the first few days of March, 2021.

The endpoints support a couple of common search parameters.

For example, searching for locations in Boston, MA

```bash
curl --location --request GET 'http://localhost:8080/fhir/Location?city=Boston&state=MA' \
--header 'Content-Type: application/fhir+json'
```

or within a certain radius:

```bash
curl --location --request GET 'http://localhost:8080/fhir/Location?near=42.4887%5C%7C-71.2837|10|mi' \
--header 'Content-Type: application/fhir+json'
```

You can also look for slots within a period of time:

```bash
curl --location --request GET 'http://localhost:8080/fhir/Slot?&state=gt2021-03-01 \
--header 'Content-Type: application/fhir+json'
```

If you want to trigger the background refersh, there's an actuator for that:

```bash
curl http://localhost:8080/actuator/refresh
```

(wait for the job to finish)

```bash
curl http://localhost:8080/fhir/Slot
```

Tada!!!!
