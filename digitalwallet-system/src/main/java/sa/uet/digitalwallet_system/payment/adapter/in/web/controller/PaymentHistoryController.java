package sa.uet.digitalwallet_system.payment.adapter.in.web.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import sa.uet.digitalwallet_system.payment.api.*;
import sa.uet.digitalwallet_system.platform.api.web.ApiResponse;
import sa.uet.digitalwallet_system.platform.api.web.ErrorResponse;

@RestController @RequestMapping(value="/api/v2/transactions",produces="application/json") @RequiredArgsConstructor
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="bearerAuth")
@ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="400",description="Invalid query/cursor",content=@Content(mediaType="application/json",schema=@Schema(implementation=ErrorResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401",description="Authentication required",content=@Content(mediaType="application/json",schema=@Schema(implementation=ErrorResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="404",description="Owned resource not found",content=@Content(mediaType="application/json",schema=@Schema(implementation=ErrorResponse.class)))})
public class PaymentHistoryController {
    private final PaymentQueryApi payments;
    @GetMapping @Operation(summary="Bounded payment history",description="Descending (created_at,id) keyset; limit defaults to 20, maximum 100. type is TOPUP/WITHDRAW/TRANSFER. from inclusive/to exclusive are ISO local history times at millisecond precision, uniquely resolvable in the server history timezone. DST gap/overlap boundaries return 400; valid bounds may span a DST transition. Cursor is bound to wallet and filters; changing limit is allowed. This is a live traversal, not a cross-request snapshot.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200",description="Payment slice")
    public ApiResponse<PaymentSlice> history(@AuthenticationPrincipal Long actor,
            @io.swagger.v3.oas.annotations.Parameter(schema=@Schema(maxLength=1024)) @RequestParam(required=false) String cursor,
            @io.swagger.v3.oas.annotations.Parameter(schema=@Schema(defaultValue="20",minimum="1",maximum="100")) @RequestParam(required=false) Integer limit,
            @RequestParam(required=false) String type,@RequestParam(required=false) String from,@RequestParam(required=false) String to) {
        return ApiResponse.success(payments.history(actor,new HistoryQuery(cursor,limit,type,from,to)));
    }
    @GetMapping("/{referenceCode}") @Operation(summary="Get an owned payment",description="Foreign and nonexistent references both return 404.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200",description="Owned payment")
    public ApiResponse<PaymentView> get(@AuthenticationPrincipal Long actor,@PathVariable String referenceCode) {
        return ApiResponse.success(payments.getOwned(actor,referenceCode));
    }
}
