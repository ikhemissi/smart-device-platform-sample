package io.github.ikhemissi.sdp.provisioning.azure;

import com.azure.core.credential.TokenCredential;
import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.HttpMethod;
import com.azure.core.http.HttpPipeline;
import com.azure.core.http.HttpPipelineBuilder;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.core.http.policy.BearerTokenAuthenticationPolicy;
import com.azure.core.http.policy.ExponentialBackoffOptions;
import com.azure.core.http.policy.RetryOptions;
import com.azure.core.http.policy.RetryPolicy;
import com.azure.core.util.BinaryData;
import java.time.Duration;
import java.util.Map;

/**
 * Minimal JSON-over-HTTPS client for an Azure REST API authenticated with Microsoft Entra ID tokens for {@code scope}
 * (ADR-0005), with bounded retries on transient failures.
 */
public final class AzureRest {

    public static final HttpHeaderName IF_MATCH = HttpHeaderName.IF_MATCH;

    /** {@code continuation} is the {@code x-ms-continuation} header of paged queries, if any. */
    public record Response(int status, String body, String etag, String continuation) {
    }

    private final HttpPipeline pipeline;
    private final String baseUrl;

    public AzureRest(String baseUrl, TokenCredential credential, String scope) {
        this.baseUrl = baseUrl;
        this.pipeline = new HttpPipelineBuilder()
                .policies(
                        new RetryPolicy(new RetryOptions(new ExponentialBackoffOptions()
                                .setMaxRetries(2).setBaseDelay(Duration.ofMillis(300)).setMaxDelay(Duration.ofSeconds(2)))),
                        new BearerTokenAuthenticationPolicy(credential, scope))
                .build();
    }

    /** Sends the request; any status is returned, only transport failures throw. */
    public Response send(HttpMethod method, String path, String jsonBody, Map<HttpHeaderName, String> headers) {
        HttpRequest request = new HttpRequest(method, baseUrl + path);
        request.setHeader(HttpHeaderName.ACCEPT, "application/json");
        if (jsonBody != null) {
            request.setHeader(HttpHeaderName.CONTENT_TYPE, "application/json");
            request.setBody(BinaryData.fromString(jsonBody));
        }
        headers.forEach(request::setHeader);
        try (HttpResponse response = pipeline.sendSync(request, com.azure.core.util.Context.NONE)) {
            String body = response.getBodyAsBinaryData() == null ? "" : response.getBodyAsBinaryData().toString();
            return new Response(response.getStatusCode(), body, response.getHeaderValue(HttpHeaderName.ETAG),
                    response.getHeaderValue(HttpHeaderName.fromString("x-ms-continuation")));
        } catch (RuntimeException e) {
            throw new AzureRestException(method + " " + path + " failed: " + e.getMessage(), 0, e);
        }
    }

    public static AzureRestException failure(String what, Response response) {
        String detail = response.body() == null ? "" : response.body();
        return new AzureRestException(what + " failed with HTTP " + response.status() + ": "
                + detail.substring(0, Math.min(300, detail.length())), response.status(), null);
    }
}
