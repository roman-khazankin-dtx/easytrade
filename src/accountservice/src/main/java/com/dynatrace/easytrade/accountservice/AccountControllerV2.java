package com.dynatrace.easytrade.accountservice;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/accounts")
@CrossOrigin
public class AccountControllerV2 {
    private static final Logger logger = LoggerFactory.getLogger(AccountController.class);
    private static final Type accountListType = new TypeToken<ArrayList<Account>>() {
    }.getType();

    // UC2 profiling defect (memory leak). Retained forever and never cleared, so
    // the heap grows monotonically with the request count on GET /accounts/{id}.
    // Named distinctly so the allocation profiler shows this frame unambiguously.
    // Always-on: profiling defects carry no activation flag or env var — scenarios
    // run on isolated, non-overlapping instances (see the design doc §2.1, §6 Q4).
    private static final List<byte[]> HEAP_RETAINER = Collections.synchronizedList(new ArrayList<>());

    private final HttpClient httpClient = HttpClient.newBuilder().build();
    private final Gson gson = new GsonBuilder().setDateFormat("yyyy-MM-dd'T'hh:mm:ss").create();
    private final String manager = System.getenv("MANAGER_HOSTANDPORT");

    @GetMapping("/{accountId}")
    public Account get(@PathVariable int accountId) throws IOException, InterruptedException {
        logger.info("Getting account data for {}", accountId);

        accumulateRequestTrace(accountId);

        // file deepcode ignore Ssrf: trusted environment variable
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format("http://%s/api/Accounts/GetAccountById/%d", manager, accountId)))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        Account account = gson.fromJson(response.body(), Account.class);

        return account;
    }

    // Distinct name so the allocation profiler shows this frame clearly.
    // Appends a 16 KB buffer per call, retained in HEAP_RETAINER indefinitely.
    private void accumulateRequestTrace(int accountId) {
        byte[] trace = new byte[16384];
        trace[0] = (byte) (accountId & 0xFF);
        trace[1] = (byte) ((accountId >> 8) & 0xFF);
        HEAP_RETAINER.add(trace);
    }

    @PutMapping(value = "/", produces = "text/plain")
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

    @GetMapping("/presets")
    public AccountsContainer get(@RequestParam Optional<Integer> limit) throws IOException, InterruptedException {
        logger.info("Getting default accounts");

        // file deepcode ignore Ssrf: trusted environment variable
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format("http://%s/api/Accounts/", manager)))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        List<Account> accountList = gson.fromJson(response.body(), accountListType);

        List<ShortAccount> accounts = accountList.stream()
                .filter(Account::isPreset)
                .limit(limit.orElse(accountList.size()))
                .map(Account::toShortAccount)
                .collect(Collectors.toList());

        return new AccountsContainer(accounts);

    }

}
