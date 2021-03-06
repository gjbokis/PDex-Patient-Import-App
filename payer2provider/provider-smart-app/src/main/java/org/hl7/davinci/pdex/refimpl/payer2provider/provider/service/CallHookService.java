package org.hl7.davinci.pdex.refimpl.payer2provider.provider.service;

import ca.uhn.fhir.rest.client.api.IGenericClient;
import org.hl7.davinci.pdex.refimpl.payer2provider.provider.dto.CurrentContextDto;
import org.hl7.davinci.pdex.refimpl.payer2provider.provider.dto.CurrentContextResponseDto;
import org.hl7.davinci.pdex.refimpl.payer2provider.provider.fhir.IGenericClientProvider;
import org.hl7.davinci.pdex.refimpl.payer2provider.provider.oauth2.context.OAuth2ClientContextHolder;
import org.hl7.davinci.pdex.refimpl.cdshooks.model.CdsRequest;
import org.hl7.davinci.pdex.refimpl.cdshooks.model.CdsResponse;
import org.hl7.davinci.pdex.refimpl.cdshooks.model.FhirAuthorization;
import org.hl7.davinci.pdex.refimpl.payer2provider.provider.fhir.FhirResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.common.OAuth2AccessToken;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Slf4j
public class CallHookService {

  private final IGenericClientProvider clientProvider;
  private final RestTemplate restTemplate;
  private final String cdsHookUri;

  public CallHookService(@Autowired IGenericClientProvider clientProvider, @Autowired RestTemplate restTemplate,
      @Value("${payer.cds-hook-uri}") String cdsHookUri) {
    this.clientProvider = clientProvider;
    this.restTemplate = restTemplate;
    this.cdsHookUri = cdsHookUri;
  }

  public CurrentContextResponseDto getCurrentContextDetails(CurrentContextDto currentContext) {
    IGenericClient client = clientProvider.client();
    Patient patient = client.read().resource(Patient.class).withId(currentContext.getPatientId()).execute();
    Practitioner practitioner = client.read().resource(Practitioner.class).withId(currentContext.getUserId()).execute();
    Encounter encounter = Optional.ofNullable(currentContext.getEncounterId()).map(a -> client.read().resource(
        Encounter.class).withId(a).execute()).orElse(null);
    List<Coverage> coverages = client.search().forResource(Coverage.class).where(
        Coverage.SUBSCRIBER.hasId(patient.getIdElement().getIdPart())).and(Coverage.STATUS.exactly().code("active")).include(
        Coverage.INCLUDE_PAYOR.asNonRecursive()).returnBundle(Bundle.class).execute().getEntry().stream().map(
        BundleEntryComponent::getResource).filter(Coverage.class::isInstance).map(Coverage.class::cast).collect(
        Collectors.toList());

    return new CurrentContextResponseDto(patient, practitioner, encounter, coverages);
  }

  //TODO: Select a CDS Hook Service URL based on a Coverage->Payer. Currently all requests will go to the same Payer
  // CDS Hook service. Even if no Coverage is present - we will just send a CDS Hook request without a subscriber ID.
  public CdsResponse callHook(String patientId, String practitionerId, String encounterId, String coverageId)
      throws FhirResourceNotFoundException {

    Assert.notNull(patientId, "Patient ID cannot be null");
    Assert.notNull(practitionerId, "Practitioner ID cannot be null");
    //Disabling this check will let us launch and test the App without encounter selection.
    //Assert.notNull(encounterId, "Encounter ID cannot be null");

    //Coverage can be null?
    //Assert.notNull(coverageId, "Coverage ID cannot be null");

    IGenericClient client = clientProvider.client();

    //Retrieve resources to check whether IDs are valid
    Patient patient = client.read().resource(Patient.class).withId(patientId).execute();
    Practitioner practitioner = client.read().resource(Practitioner.class).withId(practitionerId).execute();
    Encounter encounter = Optional.ofNullable(encounterId).map(a -> client.read().resource(Encounter.class).withId(a)
        .execute()).orElse(null);
    Coverage coverage = Optional.ofNullable(coverageId).map(a -> client.read().resource(Coverage.class).withId(a)
        .execute()).orElse(null);

    //Check resources exist
    String verifiedPatientId = Optional.ofNullable(patient).map(p -> patient.getIdElement().getIdPart()).orElseThrow(
        () -> new FhirResourceNotFoundException(patientId, Patient.class));

    String verifiedPractitionerId = Optional.ofNullable(practitioner).map(p -> practitioner.getIdElement().getIdPart())
        .orElseThrow(() -> new FhirResourceNotFoundException(practitionerId, Practitioner.class));

    //Disabling null check for encounter will let us launch and test the App without encounter selection.
    String verifiedEncounterId = Optional.ofNullable(encounter).map(p -> encounter.getIdElement().getIdPart()).orElse(
        null);
    String subscriberId = Optional.ofNullable(coverage).map(p -> coverage.getSubscriberId()).orElse(null);

    CdsRequest cdsRequest = composeCdsRequest(client, verifiedPatientId, verifiedPractitionerId, verifiedEncounterId,
                                              subscriberId);
    return getCdsResponse(cdsRequest);
  }

  private CdsRequest composeCdsRequest(IGenericClient client, String patientId, String practitionerId,
      String encounterId, String subscriberId) {
    OAuth2AccessToken accessToken = OAuth2ClientContextHolder.currentContext().getAccessToken();
    FhirAuthorization authorization = new FhirAuthorization();
    authorization.setAccessToken(accessToken.getValue());
    authorization.setTokenType(accessToken.getTokenType());

    Map<String, Object> context = new LinkedHashMap<>();
    context.put("userId", practitionerId);
    context.put("patientId", patientId);
    context.put("encounter", encounterId);
    context.put("appointments", new Object[] {});
    context.put("subscriberId", subscriberId);

    CdsRequest cdsRequest = new CdsRequest();
    cdsRequest.setHook("appointment-book");
    cdsRequest.setFhirServer(client.getServerBase());
    cdsRequest.setFhirAuthorization(authorization);
    cdsRequest.setUser(practitionerId);
    cdsRequest.setPatient(patientId);
    cdsRequest.setContext(context);
    return cdsRequest;
  }

  private CdsResponse getCdsResponse(CdsRequest cdsRequest) {
    try {
      return restTemplate.postForEntity(cdsHookUri, cdsRequest, CdsResponse.class).getBody();
    } catch (HttpClientErrorException e) {
      // TODO: To use proper error handling
      HttpStatus statusCode = e.getStatusCode();
      if (e.getResponseBodyAsByteArray().length > 0) {
        if (statusCode == HttpStatus.UNPROCESSABLE_ENTITY) {
          throw new HttpClientErrorException(HttpStatus.UNPROCESSABLE_ENTITY,
                                             "More than one record matched patient demographics data from EMR");
        } else if (statusCode == HttpStatus.NOT_FOUND) {
          throw new HttpClientErrorException(HttpStatus.NOT_FOUND,
                                             "No patient was found either by subscriber id or by EMR demographics data "
                                                 + "matching");
        } else if (statusCode == HttpStatus.UNAUTHORIZED){
          throw new HttpClientErrorException(HttpStatus.UNAUTHORIZED,
              "Unathorized during call for cds hooks");
        }
      }
      throw e;
    }
  }

}
