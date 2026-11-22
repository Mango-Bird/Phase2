package sa.uet.digitalwallet_system.payment.adapter.in.web.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Parameter;
import sa.uet.digitalwallet_system.payment.adapter.in.web.dto.response.PaymentSuccessResponse;
import org.springframework.http.MediaType;
import sa.uet.digitalwallet_system.payment.api.*;
import sa.uet.digitalwallet_system.payment.application.PaymentValidation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import sa.uet.digitalwallet_system.payment.adapter.in.web.dto.request.TopupRequest;
import sa.uet.digitalwallet_system.payment.adapter.in.web.dto.request.TransferRequest;
import sa.uet.digitalwallet_system.payment.adapter.in.web.dto.request.WithdrawRequest;
import sa.uet.digitalwallet_system.platform.api.web.ApiResponse;
import sa.uet.digitalwallet_system.platform.api.web.ErrorResponse;
import sa.uet.digitalwallet_system.payment.adapter.in.web.dto.response.TransactionResponse;
import sa.uet.digitalwallet_system.payment.application.port.in.GetTransactionsUseCase;
import sa.uet.digitalwallet_system.wallet.api.WalletQueryApi;
import sa.uet.digitalwallet_system.wallet.api.WalletView;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
@Tag(name = "Transactions", description = "Endpoints for topup, withdraw, transfer, and transaction history")
@SecurityRequirement(name = "bearerAuth")
public class TransactionController {

    private final WalletQueryApi getWalletUseCase;
    private final PaymentCommandApi payments;
    private final GetTransactionsUseCase getTransactionsUseCase;

    @PostMapping("/topup")
    @Operation(summary = "Topup wallet", description = "Transfers money from linked bank account to wallet")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Topup successful", content = @Content(schema = @Schema(implementation = PaymentSuccessResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Key reused, request in progress or concurrent update",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed / Wallet not active",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Wallet or linked bank account not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<String> topup(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "Reuse the same key for retries of the same intent", required = true,
                    schema = @Schema(type = "string", pattern = "[A-Za-z0-9._:-]{1,64}", minLength = 1, maxLength = 64))
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody TopupRequest request) {
        PaymentValidation.key(key);
        PaymentReceipt receipt = payments.topup(userId, key, new TopupInput(request.getLinkedBankAccountId(), request.getAmount(), request.getDescription()));
        return ResponseEntity.status(receipt.httpStatus()).contentType(MediaType.APPLICATION_JSON).body(receipt.successBody());
    }

    @PostMapping("/withdraw")
    @Operation(summary = "Withdraw money", description = "Transfers money from wallet to linked bank account")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Withdraw successful", content = @Content(schema = @Schema(implementation = PaymentSuccessResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Key reused, request in progress or concurrent update",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed / Wallet not active / Insufficient balance",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Wallet or linked bank account not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<String> withdraw(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "Reuse the same key for retries of the same intent", required = true,
                    schema = @Schema(type = "string", pattern = "[A-Za-z0-9._:-]{1,64}", minLength = 1, maxLength = 64))
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody WithdrawRequest request) {
        PaymentValidation.key(key);
        PaymentReceipt receipt = payments.withdraw(userId, key, new WithdrawInput(request.getLinkedBankAccountId(), request.getAmount(), request.getDescription()));
        return ResponseEntity.status(receipt.httpStatus()).contentType(MediaType.APPLICATION_JSON).body(receipt.successBody());
    }

    @PostMapping("/transfer")
    @Operation(summary = "Transfer money", description = "Transfers money from caller's wallet to another wallet by wallet number")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Transfer successful", content = @Content(schema = @Schema(implementation = PaymentSuccessResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Key reused, request in progress or concurrent update",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed / Wallet not active / Insufficient balance",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Source or target wallet not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<String> transfer(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "Reuse the same key for retries of the same intent", required = true,
                    schema = @Schema(type = "string", pattern = "[A-Za-z0-9._:-]{1,64}", minLength = 1, maxLength = 64))
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody TransferRequest request) {
        PaymentValidation.key(key);
        PaymentReceipt receipt = payments.transfer(userId, key, new TransferInput(request.getTargetWalletNumber(), request.getAmount(), request.getDescription()));
        return ResponseEntity.status(receipt.httpStatus()).contentType(MediaType.APPLICATION_JSON).body(receipt.successBody());
    }

    @GetMapping
    @Operation(summary = "Get transaction history", description = "Retrieves all inbound and outbound transactions for the current user's wallet")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Transaction history retrieved"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Wallet not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<ApiResponse<List<TransactionResponse>>> getTransactions(
            @AuthenticationPrincipal Long userId) {
        WalletView wallet = getWalletUseCase.getOwnedWallet(userId);
        List<TransactionResponse> history = getTransactionsUseCase.getTransactions(wallet.id())
                .stream()
                .map(TransactionResponse::fromDomain)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(history));
    }
}

