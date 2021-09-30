package com.aline.transactionmicroservice.service;

import com.aline.core.exception.BadRequestException;
import com.aline.core.exception.UnprocessableException;
import com.aline.core.exception.notfound.AccountNotFoundException;
import com.aline.core.model.account.Account;
import com.aline.core.model.account.AccountType;
import com.aline.core.model.account.CheckingAccount;
import com.aline.transactionmicroservice.dto.CreateTransaction;
import com.aline.transactionmicroservice.dto.MerchantResponse;
import com.aline.transactionmicroservice.dto.Receipt;
import com.aline.transactionmicroservice.exception.TransactionNotFoundException;
import com.aline.transactionmicroservice.exception.TransactionPostedException;
import com.aline.transactionmicroservice.model.Merchant;
import com.aline.transactionmicroservice.model.Transaction;
import com.aline.transactionmicroservice.model.TransactionState;
import com.aline.transactionmicroservice.model.TransactionStatus;
import com.aline.transactionmicroservice.model.TransactionType;
import com.aline.transactionmicroservice.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Service;

import javax.transaction.Transactional;
import javax.validation.Valid;

/**
 * Post transaction service handles the processing
 * and posting of transactions created by users
 * using the public Transaction API. The transaction API can
 * only be accessed by authorized users whether that be
 * members, vendors, or other authorized individuals. They
 * will be provided an API key. They will register as a vendor
 * with the bank first in order to accept payment from our cards
 * and checks. Afterwards, their systems should be able to access
 * our services to create transactions and receive funds.
 */
@Service
@RequiredArgsConstructor
@Slf4j(topic = "Transactions")
public class TransactionApi {
    private final AccountService accountService;
    private final MerchantService merchantService;
    private final TransactionRepository repository;
    private final ModelMapper mapper;

    @Transactional(rollbackOn = {
            AccountNotFoundException.class
    })
    public Transaction createTransaction(@Valid CreateTransaction createTransaction) {
        Transaction transaction = mapper.map(createTransaction, Transaction.class);
        transaction.setMethod(createTransaction.getMethod());

        if (createTransaction.getCardNumber() == null) {
            Account account = accountService.getAccountByAccountNumber(createTransaction.getAccountNumber());
            transaction.setAccount(account);
            transaction.setInitialBalance(account.getBalance());
        } else {
            throw new BadRequestException("Card services are currently unavailable. Please try again later.");
        }

        if (isMerchantTransaction(createTransaction.getType())) {
            Merchant merchant = merchantService.checkMerchant(
                    createTransaction.getMerchantCode(),
                    createTransaction.getMerchantName());
            transaction.setMerchant(merchant);
        }

        transaction.setStatus(TransactionStatus.PENDING); // Transactions will initially be pending when created
        transaction.setState(TransactionState.CREATED);
        return repository.save(transaction);
    }

    /**
     * Boolean representing whether the transaction type
     * can be performed by a merchant.
     * @param type TransactionType enum
     * @return True if the transaction type can be performed by a merchant
     */
    public boolean isMerchantTransaction(TransactionType type) {
        switch (type) {
            case PURCHASE:
            case PAYMENT:
            case REFUND:
            case VOID:
            case DEPOSIT:
                return true;
            default:
                return false;
        }
    }

    /**
     * Processes an initialized transaction
     * @param transaction   An initialized transaction.
     *                      In order to initialize a transaction,
     *                      please see {@link #createTransaction(CreateTransaction)}.
     * @return A receipt of the processed transaction.
     */
    @Transactional(rollbackOn = {
            UnprocessableException.class
    })
    public Receipt processTransaction(Transaction transaction) {

        if (transaction.getState() == TransactionState.POSTED)
            throw new UnprocessableException("Transaction is already posted. Unable to process a transaction.");

        transaction.setState(TransactionState.PROCESSING);

        // Perform the passed transaction
        performTransaction(transaction);
        validateTransaction(transaction);
        postTransaction(transaction);

        val receipt = mapper.map(transaction, Receipt.class);

        if (transaction.isMerchantTransaction()) {
            receipt.setMerchantResponse(mapper.map(
                    transaction.getMerchant(),
                    MerchantResponse.class));
        }

        return receipt;
    }

    /**
     * Approve the transaction
     * @param transaction The transaction to approve
     */
    public void approveTransaction(Transaction transaction) {
        transaction.setStatus(TransactionStatus.APPROVED);
        performTransaction(transaction);
    }

    /**
     * Deny the transaction
     * @param transaction The transaction to deny
     */
    public void denyTransaction(Transaction transaction) {
        transaction.setStatus(TransactionStatus.DENIED);
        performTransaction(transaction);
    }

    /**
     * Apply transaction increase or decrease balance to the
     * account attached to the transaction. The transaction will
     * only be performed if it has been approved.
     * <br>
     * If status is pending and the account is a checking account
     * then the available balance will be updated.
     * @param transaction The transaction to perform
     */
    public void performTransaction(Transaction transaction) {
        boolean isIncreasing = transaction.isIncreasing();
        boolean isDecreasing = transaction.isDecreasing();
        int amount = transaction.getAmount();

        Account account = transaction.getAccount();

        int postedBalance = account.getBalance();

        // If transaction is approved, decrease actual balance
        if (transaction.getStatus() == TransactionStatus.APPROVED) {
            if (isIncreasing && !isDecreasing) {
                postedBalance = account.getBalance() + amount;
            } else if (isDecreasing && !isIncreasing) {
                postedBalance = account.getBalance() - amount;
            }
        } else if (transaction.getStatus() == TransactionStatus.PENDING) {
            // If transaction is pending and account is checking, decrease available balance
            if (account.getAccountType() == AccountType.CHECKING) {
                val checkingAccount = (CheckingAccount) account;
                if (isIncreasing && !isDecreasing) {
                    postedBalance = checkingAccount.getAvailableBalance() + amount;
                } else if (isDecreasing && !isIncreasing) {
                    postedBalance = checkingAccount.getAvailableBalance() - amount;
                }
                transaction.setPostedBalance(postedBalance);
                return;
            }
        }
        transaction.setPostedBalance(postedBalance);
    }

    /**
     * Validate transaction based on account balance
     * @param transaction The transaction to validate
     */
    public void validateTransaction(Transaction transaction) {
        if (transaction.getState() != TransactionState.PROCESSING)
            throw new UnprocessableException("Transaction is in an invalid state.");
        if (transaction.getStatus() != TransactionStatus.PENDING)
            throw new UnprocessableException("Transaction already validated.");

        int balance = transaction.getPostedBalance();

        if (balance < 0) {
            denyTransaction(transaction);
        }

        // If the status is still pending after all checks
        if (transaction.getStatus() == TransactionStatus.PENDING)
            approveTransaction(transaction);
    }

    /**
     * Set the state of the transaction to POSTED and commit all changes to the database
     * @param transaction The transaction to post
     */
    public void postTransaction(Transaction transaction) {
        if (transaction.getState() == TransactionState.POSTED)
            throw new UnprocessableException("Transaction is already posted.");
        if (transaction.getState() != TransactionState.PROCESSING)
            throw new UnprocessableException("Transaction needs to be processed before it is posted.");
        if (transaction.getStatus() == TransactionStatus.PENDING)
            throw new UnprocessableException("Cannot post a transaction that is pending.");
        transaction.setState(TransactionState.POSTED);
        if (transaction.getStatus() == TransactionStatus.APPROVED) {
            Account account = transaction.getAccount();
            if (transaction.isIncreasing() && !transaction.isDecreasing()) {
                account.increaseBalance(transaction.getAmount());
                if (account.getAccountType() == AccountType.CHECKING)
                    ((CheckingAccount) account).increaseAvailableBalance(transaction.getAmount());
            } else if (transaction.isDecreasing() && !transaction.isIncreasing()) {
                account.decreaseBalance(transaction.getAmount());
                if (account.getAccountType() == AccountType.CHECKING)
                    ((CheckingAccount) account).decreaseAvailableBalance(transaction.getAmount());
            }
            log.info("Transaction approved.");
        }
        repository.save(transaction);
    }


    /**
     * Delete transaction by its ID
     * @param id The ID of the transaction to delete
     */
    public void deleteTransactionById(long id) {
        Transaction transaction = repository.findById(id).orElseThrow(TransactionNotFoundException::new);
        if (transaction.getState() != TransactionState.POSTED) {
            repository.delete(transaction);
        } else throw new TransactionPostedException();
    }

}