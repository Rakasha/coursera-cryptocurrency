import java.security.PublicKey;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;


public class TxHandler {

    private final UTXOPool utxoPool;

    /**
     * Creates a public ledger whose current UTXOPool (collection of unspent transaction outputs) is
     * {@code utxoPool}. This should make a copy of utxoPool by using the UTXOPool(UTXOPool uPool)
     * constructor.
     */
    public TxHandler(UTXOPool utxoPool) {
        this.utxoPool = new UTXOPool(utxoPool);
    }

    /**
     * @return true if:
     * (1) all outputs claimed by {@code tx} are in the current UTXO pool,
     * (2) the signatures on each input of {@code tx} are valid,
     * (3) no UTXO is claimed multiple times by {@code tx},
     * (4) all of {@code tx}s output values are non-negative, and
     * (5) the sum of {@code tx}s input values is greater than or equal to the sum of its output
     *     values; and false otherwise.
     */
    public boolean isValidTx(Transaction tx) {

        if (usesSpentOutput(tx)) {
            return false;
        }

        if (!ValidInputSignature(tx)) {
            return false;
        }

        if (hasDoubleSpendWithinTransaction(tx)) {
            return false;
        }

        if (hasNegativeOutput(tx)) {
            return false;
        }

        if (!isValidInputOutputSum(tx)) {
            return false;
        }

        return true;
    }

    private boolean ValidInputSignature(Transaction tx) {

        PublicKey pk;
        byte[] msg;
        Transaction.Input input;
        Transaction.Output output;
        UTXO utxo;

        for (int i=0; i < tx.numInputs(); i++) {
            msg = tx.getRawDataToSign(i);
            input = tx.getInput(i);
            utxo = new UTXO(input.prevTxHash, input.outputIndex);
            output = utxoPool.getTxOutput(utxo);
            pk = output.address;

            if (!Crypto.verifySignature(pk, msg, input.signature)) {
                return false;
            }
        }
        return true;
    }


    private boolean isValidInputOutputSum(Transaction tx) {

        double total_input = 0;
        Transaction.Output referencedOutput;
        for(Transaction.Input input: tx.getInputs()) {
            referencedOutput = utxoPool.getTxOutput(new UTXO(input.prevTxHash, input.outputIndex));
            total_input += referencedOutput.value;
        }

        double total_output = 0;
        for (Transaction.Output output: tx.getOutputs()) {
            total_output += output.value;
        }

        return total_input >= total_output;
    }

    private boolean hasNegativeOutput(Transaction tx) {
        for(Transaction.Output output: tx.getOutputs()) {
            if(output.value < 0) {
                return true;
            }
        }
        return false;
    }

    private boolean hasDoubleSpendWithinTransaction(Transaction tx) {
        List<UTXO> utxoList = tx.getInputs()
                                .stream()
                                .map(x -> new UTXO(x.prevTxHash, x.outputIndex))
                                .collect(Collectors.toList());

        HashSet<UTXO> utxoSet = new HashSet<>(utxoList);

        return utxoList.size() != utxoSet.size();
    }


    private boolean usesSpentOutput(Transaction tx) {
        UTXO utxoToCheck;
        for (Transaction.Input input: tx.getInputs()) {
            utxoToCheck = new UTXO(input.prevTxHash, input.outputIndex);
            if (!utxoPool.contains(utxoToCheck)) {
                return true;
            }
        }
        return false;
    }


    /**
     * Handles each epoch by receiving an unordered array of proposed transactions, checking each
     * transaction for correctness, returning a mutually valid array of accepted transactions, and
     * updating the current UTXO pool as appropriate.
     */
    public Transaction[] handleTxs(Transaction[] possibleTxs) {
        ArrayList<Transaction> validTransactions = new ArrayList<Transaction>();
        for (Transaction tx: possibleTxs) {
            if (isValidTx(tx)) {
                validTransactions.add(tx);
                updateUTXOPool(tx);
            }
        }
        return validTransactions.toArray(new Transaction[0]);
    }

    private void updateUTXOPool(Transaction tx) {
        // Remove referenced UTXOs from pool
        for (Transaction.Input input: tx.getInputs()) {
            utxoPool.removeUTXO(new UTXO(input.prevTxHash, input.outputIndex));
        }

        // Add new generated Outputs to pool
        for (int i=0; i < tx.numOutputs(); i++) {
            utxoPool.addUTXO(new UTXO(tx.getHash(), i), tx.getOutput(i));
        }
    }

}
