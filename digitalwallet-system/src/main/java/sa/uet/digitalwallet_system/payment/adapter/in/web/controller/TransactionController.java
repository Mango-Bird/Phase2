package sa.uet.digitalwallet_system.payment.adapter.in.web.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
import sa.uet.digitalwallet_system.payment.application.port.in.TopupCommand;
import sa.uet.digitalwallet_system.payment.application.port.in.TopupUseCase;
import sa.uet.digitalwallet_system.payment.application.port.in.TransferCommand;
import sa.uet.digitalwallet_system.payment.application.port.in.TransferUseCase;
import sa.uet.digitalwallet_system.payment.application.port.in.WithdrawCommand;
import sa.uet.digitalwallet_system.payment.application.port.in.WithdrawUseCase;
import sa.uet.digitalwallet_system.payment.domain.model.Transaction;
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
    private final TopupUseCase topupUseCase;
    private final WithdrawUseCase withdrawUseCase;
    private final TransferUseCase transferUseCase;
    private final GetTransactionsUseCase getTransactionsUseCase;

    @PostMapping("/topup")
    @Operation(summary = "Topup wallet", description = "Transfers money from linked bank account to wallet")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Topup successful"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed / Wallet not active",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Wallet or linked bank account not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<ApiResponse<TransactionResponse>> topup(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody TopupRequest request) {
        WalletView wallet = getWalletUseCase.getOwnedWallet(userId);
        TopupCommand command = new TopupCommand(
                userId,
                request.getLinkedBankAccountId(),
                request.getAmount(),
                request.getDescription()
        );
        Transaction tx = topupUseCase.topup(command);
        return ResponseEntity.ok(ApiResponse.success("Topup successful", TransactionResponse.fromDomain(tx)));
    }

    @PostMapping("/withdraw")
    @Operation(summary = "Withdraw money", description = "Transfers money from wallet to linked bank account")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Withdraw successful"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed / Wallet not active / Insufficient balance",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Wallet or linked bank account not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<ApiResponse<TransactionResponse>> withdraw(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody WithdrawRequest request) {
        WalletView wallet = getWalletUseCase.getOwnedWallet(userId);
        WithdrawCommand command = new WithdrawCommand(
                userId,
                request.getLinkedBankAccountId(),
                request.getAmount(),
                request.getDescription()
        );
        Transaction tx = withdrawUseCase.withdraw(command);
        return ResponseEntity.ok(ApiResponse.success("Withdraw successful", TransactionResponse.fromDomain(tx)));
    }

    @PostMapping("/transfer")
    @Operation(summary = "Transfer money", description = "Transfers money from caller's wallet to another wallet by wallet number")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Transfer successful"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed / Wallet not active / Insufficient balance",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Source or target wallet not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<ApiResponse<TransactionResponse>> transfer(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody TransferRequest request) {
        WalletView wallet = getWalletUseCase.getOwnedWallet(userId);
        TransferCommand command = new TransferCommand(
                userId,
                request.getTargetWalletNumber(),
                request.getAmount(),
                request.getDescription()
        );
        Transaction tx = transferUseCase.transfer(command);
        return ResponseEntity.ok(ApiResponse.success("Transfer successful", TransactionResponse.fromDomain(tx)));
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

