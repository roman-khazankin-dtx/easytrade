package com.dynatrace.easytrade.thirdpartyservice;

import com.dynatrace.easytrade.thirdpartyservice.models.CreditCardRequest;
import com.dynatrace.easytrade.thirdpartyservice.models.ManufactureProcess;
import com.dynatrace.easytrade.thirdpartyservice.models.StandardResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/manufacturer")
@CrossOrigin
public class ManufacturerController {
    private static final Logger logger = LoggerFactory.getLogger(ManufacturerController.class);
    private final LegacyMainframeBridge mainframeBridge;

    public ManufacturerController(LegacyMainframeBridge mainframeBridge) {
        this.mainframeBridge = mainframeBridge;
    }

    @PostMapping("")
    @Operation(summary = "Produce a new credit card")
    public ResponseEntity<StandardResponse> issueCreditCard(@RequestBody CreditCardRequest request) {
        logger.info("Starting to issue a credit card for data: " + request);

        // UC3 lock contention: hand the order to the single global legacy mainframe
        // channel before accepting it. Under concurrent load, request threads block
        // here on one monitor (off-CPU wait), while CPU stays idle. See
        // LegacyMainframeBridge.
        mainframeBridge.transmit();

        ManufactureScheduler.addProcess(new ManufactureProcess(request));

        return buildResponseEntity(HttpStatus.OK, "Credit card is being manufactured");
    }

    private ResponseEntity<StandardResponse> buildResponseEntity(HttpStatus status, String message) {
        logger.info(message);
        return ResponseEntity
                .status(status)
                .body(new StandardResponse(status.value(), message, null, null, null));
    }
}
