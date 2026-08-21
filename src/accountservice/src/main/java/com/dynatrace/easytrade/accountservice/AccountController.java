package com.dynatrace.easytrade.accountservice;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/account")
@CrossOrigin
public class AccountController {
    private static final Logger logger = LoggerFactory.getLogger(AccountController.class);
    // Retained forever while trace retention is active — never cleared.
    // Named distinctly so allocation profiler shows this frame unambiguously.
    private static final List<byte[]> HEAP_RETAINER = Collections.synchronizedList(new ArrayList<>());

    // Hidden activation for the UC2 profiling defect. Read once at startup from a
    // neutrally-named environment variable so the defect is NOT discoverable through
    // any of the app's own surfaces (feature-flag-service REST/Swagger, frontend flags
    // page). The eval harness arms it via the deployment/compose environment; toggling
    // requires a pod restart. Absent/false => normal behaviour, no heap growth.
    private static final boolean RETAIN_REQUEST_TRACES =
            Boolean.parseBoolean(System.getenv("REQUEST_TRACE_RETENTION_ENABLED"));

    private final HttpClient httpClient = HttpClient.newBuilder().build();
    private final Gson gson = new GsonBuilder().setDateFormat("yyyy-MM-dd'T'hh:mm:ss").create();
    private final String manager = System.getenv("MANAGER_HOSTANDPORT");

    @GetMapping("/{accountId}")
    public Account get(@PathVariable int accountId) throws IOException, InterruptedException {
        logger.info("Getting account data for {}", accountId);

        if (RETAIN_REQUEST_TRACES) {
            accumulateRequestTrace(accountId);
        }

        // file deepcode ignore Ssrf: trusted environment variable
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format("http://%s/api/Accounts/GetAccountById/%d", manager, accountId)))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        Account account = gson.fromJson(response.body(), Account.class);

        return account;
    }

    // Distinct name so allocation profiler shows this frame clearly.
    // Appends a 16 KB buffer per call, retained in HEAP_RETAINER indefinitely.
    private void accumulateRequestTrace(int accountId) {
        byte[] trace = new byte[16384];
        trace[0] = (byte) (accountId & 0xFF);
        trace[1] = (byte) ((accountId >> 8) & 0xFF);
        HEAP_RETAINER.add(trace);
    }

    @PutMapping(value = "/update", produces = "text/plain")
    public ResponseEntity<String> put(@RequestBody Account accountDetails) throws IOException, InterruptedException {
        logger.info("Updating account data with body: {}", gson.toJson(accountDetails));

        // file deepcode ignore Ssrf: trusted environment variable
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format("http://%s/api/Accounts/ModifyAccount", manager)))
                .PUT(HttpRequest.BodyPublishers.ofString(gson.toJson(accountDetails)))
                .header("Content-Type", "application/json")
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        return new ResponseEntity<>(response.body(), HttpStatus.valueOf(response.statusCode()));
    }
}
