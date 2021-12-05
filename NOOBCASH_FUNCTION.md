# Noobcash Function

This file explains the architecture of the Noobcash application and the main keys to its function.

Firstly, a 3-actor format is implemented:
* /user/peer,
* /user/peer/chainActor,
* /user/peer/chainActor/miner

The peer actor is responsible for messages between the different members of the cluster and communication with the other
peer actors and its child-actor, the chainActor. The chainActor implements the business part of the blockchain,
processes the transactions observed in the network or actually created by the peer actor,
keeps the wallets of all nodes, processes new blocks received by the peer actor and sends mining requests to its child-actor,
the miner. The miner actor simply exists to implement the Proof-of-Work algorithm.
As soon as the `ActorSystem` and the peer actor are created, a `publicKey-privateKey RSA Keypair` is also created and becomes known
to the chainActor and the miner.

### Initial Steps:
The bootstrap node of the noobcash cluster (NBCCluster) is declared in the `application.conf` file, which includes the 
basic application configuration, along with the actor provider, serializers, seed-nodes, etc. The communication between
the nodes is achieved by means of UDP sockets. When the bootstrap peer is created, it sends an `InitializeNoobchain` message to its 
chainActor to create the genesis block and receives an initial amount of <i>NETWORK_SIZE*100</i> NBC.

Every new member of the cluster sends an `InitJoin` message to the single seed-node, the bootstrap, and after the Akka Clustering
gossip protocol settles and all peers are informed about the new one, the bootstrap sends back an `InitJoinAck` message and sets its
state to \[Up\]. The new node receives its unique id from the bootstrap, it makes its `publicKey` known and then receives a `Map`
containing all ids so far and the respective publicKeys. The other peers receive just the new id and its publicKey. Then,
the bootstrap sends a message to its chainActor to create a new UTXOs wallet, which is a mapping between the new remotePublicKey
and an empty `Set`.

When the bootstrap sees that the network is complete, it receives the initial state from its chainActor, which is the 
genesis Block and its initial UTXOs, and broadcasts it to all nodes with a `StateBeforeInitialTransactions` message. Then,
it begins the initial transactions, which give each node 100 NBC. When these are completed, it sends everyone a `BeginTesting`
message and all peers start reading their respective <i>transactionsX.txt</i> files and exchanging NBC (of course, this step
can be omitted in a real deployment, here it serves just for the project's testing purposes). Here, the bootstrap
node's initial role is completed and from now on, it is just a normal peer.

### Transaction/Block processing:
Every node is allowed to create a transaction only if it has available funds, meaning UTXOs with a total sum that is 
greater or equal to the requested transaction amount. If these are found, they are subtracted from its UTXOs, the necessary
sender and receiver transaction outputs are created, the transaction is signed with its `privateKey` and is sent to the peer 
actor to be broadcasted to the other peers. Similarly, every peer that receives a new transaction sends it to its chainActor 
for processing. The chainActor accepts it only if the transaction isn't already part of some block and only if it finds the 
provided transaction inputs in the UTXOs of the `senderAddress`. If it finds them, they are subtracted, the necessary transaction
outputs are created and then the transaction is added to the `pendingTransactions`.

When the chainActor reaches <i>CAPACITY</i> pendingTransactions, it sends a message to its miner actor to begin the mining
process. At this point, the chainActor is in the `waitingForMinedBlock` state and every new transaction request is stashed.
In this state, it only receives new blocks, either form other peers or from its miner. When a new remote block is received, 
the chainActor processes all transactions that it hasn't already processed. There is a possibility for <i>orphaned</i> blocks
to be created, which are blocks that have been produced by the local miner but are now invalid. If this happens, the chainActor
checks the transactions in that block and if any of them are not part of the last blocks, they enter the pendingTransactions
again.


### Conflict Resolve:
When the chainActor receives a remote block that is invalid, it sends a `ResolveConflict` message to its peer, along with the
length of its chain (the largest chain will eventually be kept) and a list of hashes of all the blocks in chronological order (as an optimization some benchmarking
experiments can be conducted to find the maximum length of a fork and thus send only the last X hashes instead). It then moves
to the `resolvingConflict` state and receives neither transactions nor blocks, locally mined or remote, and both of these message
types are stashed until the conflict is resolved. Then, the peer sends a `RemoteConflictRequest` message to the other peers
and also moves to the resolvingConflict state. 

When the peer that started the consensus procedure receives an `AddExtraBlocks` or `NoExtraBlocks` message, it updates the
list of peers from which it expects an answer and updates the `maxChainLength`. When it receives <i>NETWORK_SIZE</i> answers,
depending on whether the conflict was resolved or not, it sends a `ConflictResolved` or `ConflictNotResolved` message to
its chainActor. In the case of ConflictResolved, the chainActor updates its local blockchain and the transactions of the last
blocks, processes any new transactions and adds to the pendingTransactions any transactions that are in the local blocks 
to be deleted but not in the new ones. If `pendingTransactions.length == CAPACITY`, it is legitimate to send a new `MineBlock`
message to the miner, since it knows the hash of the last valid block, which it just got.

In order to avoid deadlocks when X peers start the consensus algorithm, RemoteConflictRequest messages are always received,
no matter what the state is.

The transactions found in the last blocks are in the `txsInLastXBlocks`. In the current implementation, they include all
the transactions in the local blockchain. Again, some benchmarking could be conducted to find the max time needed for a
transaction to reach the last peer, so this structure could become a bounded FIFO queue.

