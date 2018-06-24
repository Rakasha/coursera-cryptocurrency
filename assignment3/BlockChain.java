// Block Chain should maintain only limited block nodes to satisfy the functions
// You should not have all the blocks added to the block chain in memory 
// as it would cause a memory overflow.

import java.lang.reflect.Array;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;

public class BlockChain {

    public static final int CUT_OFF_AGE = 10;

    private BlockMeta genesisBlock = null;

    private HashMap<ByteArrayWrapper, BlockMeta> blocks = new HashMap<ByteArrayWrapper, BlockMeta>();

    // stores the last block of each branch of block chains in descending order (by block height)
    private ArrayList<BlockMeta> tails = new ArrayList<BlockMeta>();
    private TransactionPool txPool = new TransactionPool();


    public class BlockMeta {
        private int blockHeight;
        public UTXOPool utxoPool;
        public Block blockData;
        public Comparator<BlockMeta> getBlockHeightComparator() {
          return Comparator.comparing(BlockMeta::getBlockHeight);
        };

        public BlockMeta(Block blockData, int blockHeight, UTXOPool utxoPool) {
            this.blockData = blockData;
            this.blockHeight = blockHeight;
            this.utxoPool = utxoPool;
        }
        public int getBlockHeight() {
            return this.blockHeight;
        }
    }

    /**
     * create an empty block chain with just a genesis block. Assume {@code genesisBlock} is a valid
     * block
     */
    public BlockChain(Block genesisBlock) {
        addGenesisBlock(genesisBlock);
    }

    private void addGenesisBlock(Block block) {

        if (this.genesisBlock != null) {
            throw new RuntimeException("Already have one genesis block !!");
        }

        // Genesis block should not contains NORMAL transaction.
        // Only coinbase transaction is allowed
        if (block.getTransactions().size() != 0) {
            throw new RuntimeException("Invalid Genesis Block: contains general transactions");
        }

        Transaction coinbase = block.getCoinbase();
        ArrayList<Transaction.Output> outs = coinbase.getOutputs();
        assert outs.size() == 1;

        UTXOPool utxoPool = new UTXOPool();
        utxoPool.addUTXO(new UTXO(coinbase.getHash(), 0), outs.get(0));

        BlockMeta blockMeta = new BlockMeta(block, 0, utxoPool);
        this.genesisBlock = blockMeta;
        this.tails.add(blockMeta);
        ByteArrayWrapper blockHash = new ByteArrayWrapper(block.getHash());
        this.blocks.put(blockHash, blockMeta);
    }

    public void pruning() {
        // TODO: Remove old blocks, also update the records in UTXOPools, blocks, block heights, tails

        ArrayList<ByteArrayWrapper> blocksToRemove = new ArrayList<>();
        for(ByteArrayWrapper hash: this.blocks.keySet()) {
            if (this.blocks.get(hash).getBlockHeight() < (getMaxBlockHeight() - CUT_OFF_AGE)) {
                blocksToRemove.add(hash);
            }
        }
        for(ByteArrayWrapper hash: blocksToRemove) {
            BlockMeta b = this.blocks.remove(hash);
            // Try to remove the block from the recorded tails of branches
            this.tails.remove(b);
        }
    }

    /** Get the maximum height block */
    public Block getMaxHeightBlock() {
        // If block height are the same, return the oldest block
        return getMaxHeightBlockMeta().blockData;
    }

    public BlockMeta getMaxHeightBlockMeta() {
        return this.tails.get(0);
    }

    /** Get the UTXOPool for mining a new block on top of max height block */
    public UTXOPool getMaxHeightUTXOPool() {
        return getMaxHeightBlockMeta().utxoPool;
    }

    /** Get the transaction pool to mine a new block */
    public TransactionPool getTransactionPool() {
        return this.txPool;
    }

    /**
     * Add {@code block} to the block chain if it is valid. For validity, all transactions should be
     * valid and block should be at {@code height > (maxHeight - CUT_OFF_AGE)}.
     * 
     * <p>
     * For example, you can try creating a new block over the genesis block (block height 2) if the
     * block chain height is {@code <=
     * CUT_OFF_AGE + 1}. As soon as {@code height > CUT_OFF_AGE + 1}, you cannot create a new block
     * at height 2.
     * 
     * @return true if block is successfully added
     */
    public boolean addBlock(Block block) {
        // TODO: IMPLEMENT THIS


        if (isValidBlock(block)) {
            ByteArrayWrapper blockHash = new ByteArrayWrapper(block.getHash());

            // remove tx from tx Pool, create a new utxo
            TxHandler txHandler = new TxHandler(getMaxHeightUTXOPool());
            Transaction[] confirmedTXs;
            confirmedTXs = txHandler.handleTxs(block.getTransactions().toArray(new Transaction[0]));

            for (Transaction tx : confirmedTXs) {
                this.txPool.removeTransaction(tx.getHash());
            }
            ByteArrayWrapper prevBlockHash = new ByteArrayWrapper(block.getPrevBlockHash());
            int blockHeight = this.blocks.get(prevBlockHash).blockHeight + 1;

            // Add UTXO in coinbase
            Transaction coinbase = block.getCoinbase();
            ArrayList<Transaction.Output> outs = coinbase.getOutputs();
            assert outs.size() == 1;
            UTXOPool utxoPool = txHandler.getUTXOPool();
            utxoPool.addUTXO(new UTXO(coinbase.getHash(), 0), outs.get(0));

            BlockMeta blockMeta = new BlockMeta(block, blockHeight, txHandler.getUTXOPool());
            this.blocks.put(blockHash, blockMeta);

            // remove current working block from tails, add new block to tails, sort them
            this.tails.remove(0);
            this.tails.add(blockMeta);
            this.tails.sort(Comparator.comparing(BlockMeta::getBlockHeight));
            // pruning
            pruning();

            return true;
        } else {
            return false;
        }
    }



    /*  Get the block height of current working block */
    private int getMaxBlockHeight() {
        return this.tails.get(0).blockHeight;
    }


    private boolean isValidBlock(Block block) {
        // Genesis block is not allowed to be added
        if (block.getPrevBlockHash() == null) {
            System.out.println("[Invalid] previous block hash is null");
            return false;
        }
        if (block.getHash() == null) {
            System.out.println("[Invalid] block hash is null");
            return false;
        }

        ByteArrayWrapper blockHash = new ByteArrayWrapper(block.getHash());
        ByteArrayWrapper prevBlockHash = new ByteArrayWrapper(block.getPrevBlockHash());

        BlockMeta prevBlock = this.blocks.get(prevBlockHash);
        if (prevBlock == null) {
            System.out.println("[Invalid] Cannot find previous block in blockchain");
            return false;
        }

        // ===== Check if its already in the block chain =====
        if (this.blocks.containsKey(blockHash)) {
            System.out.println("[Invalid] block already exist in blockchain");
            return false;
        }

        // ===== Ensure block_height > (maxHeight - CUT_OFF_AGE) =====
        int blockHeight = prevBlock.getBlockHeight() + 1;
        if (blockHeight <= ( getMaxBlockHeight() - CUT_OFF_AGE)) {
            System.out.println("[Invalid] below the CUT_OFF threshold");
            return false;
        }

        // ===== Verify transactions contain in block =====
        TxHandler txhandler = new TxHandler(getMaxHeightUTXOPool());
        Transaction[] validTx = txhandler.handleTxs(block.getTransactions().toArray(new Transaction[0]));
        return validTx.length == block.getTransactions().size();

    }


    /** Add a transaction to the transaction pool */
    public void addTransaction(Transaction tx) {
        this.txPool.addTransaction(tx);
    }
}