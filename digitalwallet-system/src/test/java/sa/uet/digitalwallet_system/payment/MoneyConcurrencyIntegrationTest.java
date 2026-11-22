package sa.uet.digitalwallet_system.payment;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.dao.OptimisticLockingFailureException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import sa.uet.digitalwallet_system.payment.api.*;
import sa.uet.digitalwallet_system.payment.application.port.out.*;
import sa.uet.digitalwallet_system.payment.domain.model.Transaction;
import sa.uet.digitalwallet_system.wallet.api.*;
import sa.uet.digitalwallet_system.wallet.application.port.out.SaveWalletPort;
import sa.uet.digitalwallet_system.wallet.domain.model.Wallet;
import sa.uet.digitalwallet_system.banking.application.port.out.LoadLinkedBankPort;
import sa.uet.digitalwallet_system.banking.application.port.in.UnlinkBankUseCase;
import sa.uet.digitalwallet_system.banking.api.BankEligibilityApi;
import sa.uet.digitalwallet_system.platform.api.error.ModuleFailure;
import java.math.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MoneyConcurrencyIntegrationTest extends PaymentDatabaseHttpTestSupport {
    @MockitoSpyBean WalletPostingApi posting;
    @MockitoSpyBean SaveWalletPort saves;
    @MockitoSpyBean SaveTransactionPort history;
    @MockitoSpyBean LoadLinkedBankPort banks;
    @MockitoSpyBean PaymentRetryDelayPort delay;
    @Autowired UnlinkBankUseCase unlink;
    @Autowired BankEligibilityApi eligibility;
    @Autowired EntityManagerFactory emf;
    WalletPostingApi postingTarget;
    SaveWalletPort saveTarget;
    SaveTransactionPort historyTarget;
    LoadLinkedBankPort bankTarget;
    PaymentRetryDelayPort delayTarget;

    @BeforeEach void targetsAndThirdWallet() {
        postingTarget=AopTestUtils.getUltimateTargetObject(posting);
        saveTarget=AopTestUtils.getUltimateTargetObject(saves);
        historyTarget=AopTestUtils.getUltimateTargetObject(history);
        bankTarget=AopTestUtils.getUltimateTargetObject(banks);
        delayTarget=AopTestUtils.getUltimateTargetObject(delay);
        addWallet(33,303,"THIRD",0);
    }
    void addWallet(long wallet, long user, String number, long balance) {
        jdbc.update("INSERT INTO users(id,username,password_hash,full_name,email,role,created_at) VALUES (?,?, 'hash','Synthetic',?,'ROLE_USER',UTC_TIMESTAMP())",user,"user"+user,"user"+user+"@example.test");
        jdbc.update("INSERT INTO wallets(id,user_id,wallet_number,balance,status,version,created_at,updated_at) VALUES (?,?,?,?,'ACTIVE',0,UTC_TIMESTAMP(),UTC_TIMESTAMP())",wallet,user,number,balance);
    }
    long version(long id) { return jdbc.queryForObject("SELECT version FROM wallets WHERE id=?",Long.class,id); }
    BigInteger total() { return jdbc.queryForObject("SELECT SUM(balance) FROM wallets",BigDecimal.class).toBigIntegerExact(); }
    void noEffects() {
        assertThat(count("transactions")).isZero(); assertThat(count("idempotency_requests")).isZero();
        assertThat(balance(11)).isEqualTo(1000); assertThat(balance(22)).isEqualTo(500); assertThat(balance(33)).isZero();
        assertThat(version(11)).isZero(); assertThat(version(22)).isZero(); assertThat(version(33)).isZero();
    }
    record Outcome(boolean success, String code) {}
    Outcome execute(Callable<PaymentReceipt> request) throws Exception {
        try { var receipt=request.call(); assertThat(receipt.httpStatus()).isEqualTo(200); return new Outcome(true,"OK"); }
        catch(ModuleFailure ex) { return new Outcome(false,ex.code()==null?ex.kind().name():ex.code()); }
    }
    void firstSnapshotsTogether() {
        var read=new AtomicInteger(); var barrier=new CyclicBarrier(2);
        doAnswer(call -> {
            var snapshot=(PostingWallet)call.callRealMethod();
            if(read.incrementAndGet()<=2) barrier.await(10,TimeUnit.SECONDS);
            return snapshot;
        }).when(postingTarget).loadOwnedForPosting(anyLong());
    }
    List<Outcome> pair(Callable<PaymentReceipt> first, Callable<PaymentReceipt> second) throws Exception {
        var workers=Executors.newFixedThreadPool(2);
        try {
            var a=workers.submit(() -> execute(first)); var b=workers.submit(() -> execute(second));
            return List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
        } finally { workers.shutdownNow(); }
    }
    List<EntityManager> recordAttemptContexts() {
        List<EntityManager> contexts=Collections.synchronizedList(new ArrayList<>());
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(jdbc.queryForObject("SELECT @@transaction_isolation",String.class)).isEqualTo("READ-COMMITTED");
            contexts.add(EntityManagerFactoryUtils.getTransactionalEntityManager(emf));
            return call.callRealMethod();
        }).when(storeTarget).claim(anyLong(),any(),anyString(),anyString());
        return contexts;
    }

    @Test void competing80And70From100HaveExactlyOneSuccess() throws Exception {
        jdbc.update("UPDATE wallets SET balance=100 WHERE id=11");
        firstSnapshotsTogether();
        var outcomes=pair(() -> api.transfer(101,"spend80",new TransferInput("TARGET",80,null)),
                () -> api.transfer(101,"spend70",new TransferInput("THIRD",70,null)));
        assertThat(outcomes.stream().filter(Outcome::success).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> !o.success()).map(Outcome::code)).containsExactly("INSUFFICIENT_BALANCE");
        assertThat(balance(11)).isIn(20L,30L);
        long expectedCredit=100-balance(11);
        assertThat((balance(22)-500)+balance(33)).isEqualTo(expectedCredit);
        assertThat(count("transactions")).isEqualTo(1); assertThat(count("idempotency_requests")).isEqualTo(1);
        assertThat(total()).isEqualTo(BigInteger.valueOf(600));
    }
    @Test void concurrentSpendingExactlyTheBalanceCanBothComplete() throws Exception {
        jdbc.update("UPDATE wallets SET balance=100 WHERE id=11"); firstSnapshotsTogether();
        var outcomes=pair(() -> api.transfer(101,"spend60",new TransferInput("TARGET",60,null)),
                () -> api.transfer(101,"spend40",new TransferInput("THIRD",40,null)));
        assertThat(outcomes).allMatch(Outcome::success);
        assertThat(balance(11)).isZero(); assertThat(balance(22)).isEqualTo(560); assertThat(balance(33)).isEqualTo(40);
        assertThat(count("transactions")).isEqualTo(2); assertThat(count("idempotency_requests")).isEqualTo(2);
        assertThat(version(11)).isEqualTo(2); assertThat(total()).isEqualTo(BigInteger.valueOf(600));
    }
    @Test void oppositeTransfersPreserveTotalAndFinishWithoutPartialWrites() throws Exception {
        jdbc.update("UPDATE wallets SET balance=1000 WHERE id IN (11,22)"); firstSnapshotsTogether();
        var outcomes=pair(() -> api.transfer(101,"forward",new TransferInput("TARGET",10,null)),
                () -> api.transfer(202,"backward",new TransferInput("SOURCE",10,null)));
        assertThat(outcomes).allMatch(Outcome::success);
        assertThat(balance(11)).isEqualTo(1000); assertThat(balance(22)).isEqualTo(1000);
        assertThat(count("transactions")).isEqualTo(2); assertThat(count("idempotency_requests")).isEqualTo(2);
        assertThat(version(11)).isEqualTo(2); assertThat(version(22)).isEqualTo(2);
        assertThat(total()).isEqualTo(BigInteger.valueOf(2000));
    }
    @Test void oppositeTransfersAcrossTwoJvmProcessesBothCommitSafely() throws Exception {
        jdbc.update("UPDATE wallets SET balance=1000 WHERE id IN (11,22)");
        var ready=new CyclicBarrier(2); var workers=Executors.newFixedThreadPool(2);
        try {
            var forward=workers.submit(() -> { ready.await(10,TimeUnit.SECONDS); return send(port,101,"transfer","cross-forward","{\"targetWalletNumber\":\"TARGET\",\"amount\":10}"); });
            var backward=workers.submit(() -> { ready.await(10,TimeUnit.SECONDS); return send(secondPort,202,"transfer","cross-backward","{\"targetWalletNumber\":\"SOURCE\",\"amount\":10}"); });
            assertThat(forward.get(20,TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(backward.get(20,TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        } finally { workers.shutdownNow(); }
        assertThat(balance(11)).isEqualTo(1000); assertThat(balance(22)).isEqualTo(1000);
        assertThat(count("transactions")).isEqualTo(2); assertThat(count("idempotency_requests")).isEqualTo(2);
        assertThat(version(11)).isEqualTo(2); assertThat(version(22)).isEqualTo(2);
        assertThat(total()).isEqualTo(BigInteger.valueOf(2000));
    }

    @Test void lowerWalletIdIsFlushedFirstEvenWhenItIsTheReceiver() {
        var ids=new ArrayList<Long>();
        doAnswer(call -> { var saved=(Wallet)call.callRealMethod(); ids.add(saved.getId()); assertThat(balance(saved.getId())).isEqualTo(saved.getBalance()); return saved; })
                .when(saveTarget).save(any(Wallet.class));
        api.transfer(202,"receiver-first",new TransferInput("SOURCE",100,null));
        assertThat(ids).containsExactly(11L,22L);
        assertThat(balance(11)).isEqualTo(1100); assertThat(balance(22)).isEqualTo(400);
        assertThat(total()).isEqualTo(BigInteger.valueOf(1500));
    }
    @Test void hotReceiverAcrossTwoJvmProcessesHasNoLostMoney() throws Exception {
        int senders=8;
        for(int i=0;i<senders;i++) addWallet(100+i,1000+i,"HOT_SOURCE_"+i,100);
        BigInteger before=total(); var barrier=new CyclicBarrier(senders); var workers=Executors.newFixedThreadPool(senders);
        try {
            List<Future<Boolean>> requests=new ArrayList<>();
            for(int i=0;i<senders;i++) {
                final int index=i;
                requests.add(workers.submit(() -> {
                    barrier.await(10,TimeUnit.SECONDS);
                    var response=send(index%2==0?port:secondPort,1000+index,"transfer","hot-"+index,"{\"targetWalletNumber\":\"THIRD\",\"amount\":10}");
                    assertThat(response.statusCode()).isIn(200,409);
                    if(response.statusCode()==409) assertThat(JSON.readTree(response.body()).get("code").asText()).isIn("CONCURRENT_UPDATE","RESOURCE_BUSY");
                    boolean success=response.statusCode()==200;
                    assertThat(balance(100+index)).isEqualTo(success?90:100);
                    return success;
                }));
            }
            long successes=0; for(var request:requests) if(request.get(30,TimeUnit.SECONDS)) successes++;
            assertThat(successes).isPositive(); assertThat(balance(33)).isEqualTo(successes*10);
            assertThat(count("transactions")).isEqualTo(successes); assertThat(count("idempotency_requests")).isEqualTo(successes);
            assertThat(version(33)).isEqualTo(successes); assertThat(total()).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT MIN(balance) FROM wallets",Long.class)).isNotNegative();
        } finally { workers.shutdownNow(); }
    }

    @Test void retryStartsNewContextsAndRereadsMoneyAfterRollback() throws Exception {
        var contexts=recordAttemptContexts(); var first=new AtomicBoolean(true);
        doAnswer(call -> { var saved=call.callRealMethod(); if(first.getAndSet(false)) throw new OptimisticLockingFailureException("injected stale after SQL"); return saved; })
                .when(saveTarget).save(any(Wallet.class));
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(count("idempotency_requests")).isZero(); assertThat(balance(11)).isEqualTo(1000); assertThat(version(11)).isZero();
            api.transfer(101,"external-commit",new TransferInput("THIRD",950,null));
            return null;
        }).when(delayTarget).pauseBeforeRetry(2);
        var outcome=execute(() -> api.transfer(101,"freshness",new TransferInput("TARGET",100,null)));
        assertThat(outcome.code()).isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(contexts).hasSize(3);
        var distinct=Collections.newSetFromMap(new IdentityHashMap<EntityManager,Boolean>()); distinct.addAll(contexts); assertThat(distinct).hasSize(3);
        verify(delayTarget).pauseBeforeRetry(2); verify(delayTarget,never()).pauseBeforeRetry(3);
        assertThat(balance(11)).isEqualTo(50); assertThat(balance(33)).isEqualTo(950); assertThat(balance(22)).isEqualTo(500);
        assertThat(count("transactions")).isEqualTo(1); assertThat(count("idempotency_requests")).isEqualTo(1);
        assertThat(total()).isEqualTo(BigInteger.valueOf(1500));
    }
    @Test void threeOptimisticAttemptsAreBoundedAndDoNotLeaveWrites() throws Exception {
        var contexts=recordAttemptContexts();
        doAnswer(call -> { call.callRealMethod(); throw new OptimisticLockingFailureException("injected stale after debit"); })
                .when(saveTarget).save(any(Wallet.class));
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); noEffects(); return null;
        }).when(delayTarget).pauseBeforeRetry(anyInt());
        var response=send(port,101,"transfer","exhaust","{\"targetWalletNumber\":\"TARGET\",\"amount\":100}");
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(JSON.readTree(response.body()).get("code").asText()).isEqualTo("CONCURRENT_UPDATE");
        assertThat(JSON.readTree(response.body()).get("retryable").asBoolean()).isTrue();
        assertThat(JSON.readTree(response.body()).get("traceId").asText()).isNotBlank();
        assertThat(contexts).hasSize(3);
        var distinct=Collections.newSetFromMap(new IdentityHashMap<EntityManager,Boolean>()); distinct.addAll(contexts); assertThat(distinct).hasSize(3);
        verify(delayTarget).pauseBeforeRetry(2); verify(delayTarget).pauseBeforeRetry(3); noEffects();
    }
    @Test void recognizedDeadlockFailureIsRetriedAfterRollback() {
        var attempts=new AtomicInteger();
        doAnswer(call -> { call.callRealMethod(); if(attempts.incrementAndGet()==1) throw new org.springframework.dao.CannotAcquireLockException("deadlock",new java.sql.SQLException("deadlock","40001",1213)); return null; })
                .when(storeTarget).claim(anyLong(),any(),anyString(),anyString());
        api.transfer(101,"deadlock-classification",new TransferInput("TARGET",100,null));
        assertThat(attempts.get()).isEqualTo(2); verify(delayTarget).pauseBeforeRetry(2);
        assertThat(balance(11)).isEqualTo(900); assertThat(count("transactions")).isEqualTo(1); assertThat(count("idempotency_requests")).isEqualTo(1);
    }
    @Test void actualMysqlDeadlockRetriesOnlyAfterTheVictimTransactionRollsBack() throws Exception {
        for(int i=0;i<30;i++) jdbc.update("INSERT INTO users(id,username,password_hash,full_name,email,role,created_at) VALUES (?,?,'hash','Deadlock fixture',?,'ROLE_USER',UTC_TIMESTAMP())",4000+i,"deadlock"+i,"deadlock"+i+"@example.test");
        var holderReady=new CountDownLatch(1); var sourceWritten=new CountDownLatch(1);
        var externalAttempted=new CountDownLatch(1); var holderFinished=new CountDownLatch(1);
        var firstSource=new AtomicBoolean(true); var deadlockObserved=new AtomicBoolean();
        var contexts=recordAttemptContexts();
        doAnswer(call -> {
            try {
                var saved=(Wallet)call.callRealMethod();
                if(saved.getId()==11 && firstSource.getAndSet(false)) {
                    sourceWritten.countDown(); assertThat(externalAttempted.await(10,TimeUnit.SECONDS)).isTrue();
                }
                return saved;
            } catch(RuntimeException ex) {
                if(sa.uet.digitalwallet_system.platform.api.error.SqlFailures.isMySqlDeadlock(ex)) deadlockObserved.set(true);
                throw ex;
            }
        }).when(saveTarget).save(any(Wallet.class));
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(holderFinished.await(10,TimeUnit.SECONDS)).isTrue();
            assertThat(balance(11)).isEqualTo(1000); assertThat(count("idempotency_requests")).isZero();
            return null;
        }).when(delayTarget).pauseBeforeRetry(2);
        var holder=CompletableFuture.runAsync(() -> {
            try {
                transaction.executeWithoutResult(status -> {
                    jdbc.queryForObject("SELECT id FROM wallets WHERE id=22 FOR UPDATE",Long.class);
                    // Make the external transaction heavier, so InnoDB chooses the payment as victim.
                    jdbc.update("UPDATE users SET full_name='Holding deadlock fixture' WHERE id BETWEEN 4000 AND 4029");
                    holderReady.countDown();
                    try { assertThat(sourceWritten.await(10,TimeUnit.SECONDS)).isTrue(); }
                    catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new RuntimeException(ex); }
                    externalAttempted.countDown();
                    jdbc.queryForObject("SELECT id FROM wallets WHERE id=11 FOR UPDATE",Long.class);
                    status.setRollbackOnly();
                });
            } finally { holderFinished.countDown(); }
        });
        try {
            assertThat(holderReady.await(10,TimeUnit.SECONDS)).isTrue();
            api.transfer(101,"real-mysql-deadlock",new TransferInput("TARGET",100,null));
            holder.get(15,TimeUnit.SECONDS);
        } finally { sourceWritten.countDown(); externalAttempted.countDown(); }
        assertThat(deadlockObserved.get()).isTrue(); assertThat(contexts).hasSize(2);
        verify(delayTarget).pauseBeforeRetry(2); verify(delayTarget,never()).pauseBeforeRetry(3);
        assertThat(balance(11)).isEqualTo(900); assertThat(balance(22)).isEqualTo(600);
        assertThat(count("transactions")).isEqualTo(1); assertThat(count("idempotency_requests")).isEqualTo(1);
        assertThat(total()).isEqualTo(BigInteger.valueOf(1500));
    }

    @Test void moneyOverflowIsNotRetriedAndDoesNotPersistAKey() throws Exception {
        jdbc.update("UPDATE wallets SET balance=? WHERE id=22",Long.MAX_VALUE);
        var result=execute(() -> api.transfer(101,"overflow",new TransferInput("TARGET",1,null)));
        assertThat(result.code()).isEqualTo("MONEY_OVERFLOW");
        assertThat(balance(11)).isEqualTo(1000); assertThat(balance(22)).isEqualTo(Long.MAX_VALUE);
        assertThat(version(11)).isZero(); assertThat(version(22)).isZero();
        assertThat(count("transactions")).isZero(); assertThat(count("idempotency_requests")).isZero();
        verifyNoInteractions(delayTarget); verify(saveTarget,never()).save(any());
    }
    @Test void topupOverflowReturnsBusinessErrorBeforeAnyMoneyWrite() throws Exception {
        jdbc.update("UPDATE wallets SET balance=? WHERE id=11",Long.MAX_VALUE);
        var response=send(secondPort,101,"topup","topup-overflow","{\"linkedBankAccountId\":31,\"amount\":1}");
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(response.body()).get("code").asText()).isEqualTo("MONEY_OVERFLOW");
        assertThat(JSON.readTree(response.body()).get("retryable").asBoolean()).isFalse();
        assertThat(balance(11)).isEqualTo(Long.MAX_VALUE); assertThat(version(11)).isZero();
        assertThat(count("transactions")).isZero(); assertThat(count("idempotency_requests")).isZero();
    }
    @Test void maximumAmountCanBeCreditedAndWithdrawnExactly() {
        jdbc.update("UPDATE wallets SET balance=0 WHERE id=11");
        api.topup(101,"max-credit",new TopupInput(31,Long.MAX_VALUE,null)); assertThat(balance(11)).isEqualTo(Long.MAX_VALUE);
        api.withdraw(101,"max-debit",new WithdrawInput(31,Long.MAX_VALUE,null)); assertThat(balance(11)).isZero();
        assertThat(count("transactions")).isEqualTo(2); assertThat(count("idempotency_requests")).isEqualTo(2); assertThat(version(11)).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(strings={"AFTER_DEBIT","AFTER_CREDIT","AFTER_HISTORY","BEFORE_COMMIT"})
    void everyFaultPointRollsBackMoneyHistoryAndKey(String point) {
        var reached=new AtomicBoolean();
        if(point.equals("AFTER_DEBIT")||point.equals("AFTER_CREDIT")) {
            doAnswer(call -> {
                var saved=(Wallet)call.callRealMethod();
                if(saved.getId()==(point.equals("AFTER_DEBIT")?11L:22L)) { reached.set(true); assertThat(balance(saved.getId())).isEqualTo(saved.getBalance()); throw new InjectedFault(point); }
                return saved;
            }).when(saveTarget).save(any(Wallet.class));
        } else if(point.equals("AFTER_HISTORY")) {
            doAnswer(call -> { var tx=call.callRealMethod(); reached.set(true); assertThat(count("transactions")).isEqualTo(1); throw new InjectedFault(point); })
                    .when(historyTarget).save(any(Transaction.class));
        } else {
            doAnswer(call -> {
                call.callRealMethod();
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    public void beforeCommit(boolean readOnly) { reached.set(true); assertThat(count("transactions")).isEqualTo(1); assertThat(jdbc.queryForObject("SELECT status FROM idempotency_requests",String.class)).isEqualTo("COMPLETED"); throw new InjectedFault(point); }
                }); return null;
            }).when(storeTarget).complete(anyLong(),any(),anyString(),anyLong(),anyString(),anyString());
        }
        assertThatThrownBy(() -> api.transfer(101,"fault-"+point,new TransferInput("TARGET",100,null))).hasStackTraceContaining("injected C05 "+point);
        assertThat(reached.get()).isTrue(); noEffects(); verifyNoInteractions(delayTarget);
    }
    static class InjectedFault extends RuntimeException { InjectedFault(String point) { super("injected C05 "+point); } }

    void bankRace(boolean paymentFirst) throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var secondEntered=new CountDownLatch(1); var calls=new AtomicInteger();
        doAnswer(call -> {
            int order=calls.incrementAndGet();
            if(order==2) secondEntered.countDown();
            var row=call.callRealMethod();
            if(order==1) { entered.countDown(); if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("bank test latch timeout"); }
            return row;
        }).when(bankTarget).lockByIdAndWalletId(31L,11L);
        var workers=Executors.newFixedThreadPool(2);
        Future<?> first=null, secondRequest=null;
        try {
            first=workers.submit(() -> { if(paymentFirst) return api.topup(101,"bank-race",new TopupInput(31,100,null)); unlink.unlink(31L,11L); return null; });
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            secondRequest=workers.submit(() -> { if(paymentFirst) { unlink.unlink(31L,11L); return null; } return execute(() -> api.topup(101,"bank-race",new TopupInput(31,100,null))); });
            assertThat(secondEntered.await(10,TimeUnit.SECONDS)).isTrue();
            Future<?> waiting=secondRequest;
            assertThatThrownBy(() -> waiting.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown(); first.get(15,TimeUnit.SECONDS); Object result=secondRequest.get(15,TimeUnit.SECONDS);
            if(!paymentFirst) assertThat(((Outcome)result).code()).isEqualTo("BANK_NOT_ACTIVE");
        } finally { release.countDown(); workers.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT status FROM linked_bank_accounts WHERE id=31",String.class)).isEqualTo("UNLINKED");
        assertThat(balance(11)).isEqualTo(paymentFirst?1100:1000);
        assertThat(count("transactions")).isEqualTo(paymentFirst?1:0); assertThat(count("idempotency_requests")).isEqualTo(paymentFirst?1:0);
    }
    @Test void paymentFirstHoldsBankLockUntilCommitAndUnlinkWaits() throws Exception { bankRace(true); }
    @Test void unlinkFirstPreventsANewPaymentFromUsingTheOldActiveState() throws Exception { bankRace(false); }
    @Test void bankLockTimeoutIsResourceBusyWithoutImplicitRetry() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var holder=CompletableFuture.runAsync(() -> transaction.executeWithoutResult(status -> {
            banks.lockByIdAndWalletId(31L,11L); entered.countDown();
            try { if(!release.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("bank latch timeout"); }
            catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new RuntimeException(ex); }
            status.setRollbackOnly();
        }));
        try {
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            var response=send(port,101,"topup","bank-busy",TOPUP);
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(JSON.readTree(response.body()).get("code").asText()).isEqualTo("RESOURCE_BUSY");
            assertThat(JSON.readTree(response.body()).get("retryable").asBoolean()).isTrue();
            assertThat(JSON.readTree(response.body()).get("message").asText()).isEqualTo("Bank link is busy; retry after backoff");
            verifyNoInteractions(delayTarget); noEffects();
        } finally { release.countDown(); holder.get(20,TimeUnit.SECONDS); }
    }
    @Test void bankPostingApiRequiresTheCallerTransaction() {
        assertThatThrownBy(() -> eligibility.requireActiveForPosting(31,11)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test void unlinkHttpTimeoutWhilePaymentHoldsBankLockCanBeRetriedWithoutKey() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        doAnswer(call -> {
            var row=call.callRealMethod();
            entered.countDown();
            if(!release.await(20,TimeUnit.SECONDS)) throw new IllegalStateException("payment bank latch timeout");
            return row;
        }).when(bankTarget).lockByIdAndWalletId(31L,11L);
        var payment=CompletableFuture.supplyAsync(() -> api.topup(101,"unlink-http-timeout",new TopupInput(31,100,null)));
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:"+secondPort+"/api/v1/linked-banks/31"))
                .timeout(java.time.Duration.ofSeconds(15)).header("Authorization","Bearer "+token(101)).DELETE().build();
        assertThat(request.headers().firstValue("Idempotency-Key")).isEmpty();
        try {
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            var response=HTTP.send(request,java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(409);
            var error=JSON.readTree(response.body());
            assertThat(error.get("code").asText()).isEqualTo("RESOURCE_BUSY");
            assertThat(error.get("retryable").asBoolean()).isTrue();
            assertThat(error.get("message").asText()).isEqualTo("Bank link is busy; retry after backoff");
            assertThat(payment.isDone()).isFalse();
            assertThat(jdbc.queryForObject("SELECT status FROM linked_bank_accounts WHERE id=31",String.class)).isEqualTo("ACTIVE");
            noEffects(); verifyNoInteractions(delayTarget);
        } finally { release.countDown(); payment.get(20,TimeUnit.SECONDS); }
        oneEffect(1100);
        var retried=HTTP.send(request,java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(retried.statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM linked_bank_accounts WHERE id=31",String.class)).isEqualTo("UNLINKED");
        oneEffect(1100);
    }

    @Test void runtimeOpenApiDeclaresUnlinkResourceBusyAndNoIdempotencyKey() throws Exception {
        var response=HTTP.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:"+port+"/v3/api-docs")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        var delete=JSON.readTree(response.body()).path("paths").path("/api/v1/linked-banks/{id}").path("delete");
        var busy=delete.path("responses").path("409");
        assertThat(busy.path("content").path("application/json").path("schema").path("$ref").asText()).endsWith("/ErrorResponse");
        assertThat(busy.path("description").asText()).contains("RESOURCE_BUSY","retryable=true","without Idempotency-Key");
        assertThat(delete.path("description").asText()).contains("Retry DELETE after backoff without an Idempotency-Key");
        for(var parameter:delete.path("parameters")) assertThat(parameter.path("name").asText()).isNotEqualTo("Idempotency-Key");
    }
}
