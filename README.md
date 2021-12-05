# Noobcash: a complete decentralized cryptocurrency

The goal of this project is to create Noobcash, a simple, 
bitcoin-like blockchain system that records all transactions of 
those involved and achieves consensus through the 
<a href="https://en.wikipedia.org/wiki/Proof_of_work">Proof-of-Work</a>
algorithm. For the implementation, the Scala Language and the 
Akka framework will be used, aiming to explore as many of the 
capabilities they offer as possible. 

To test the performance of this solution, as far as its throughput 
and average block time are concerned, experiments were conducted
with varying block capacity and mining difficulty values. To test its 
scalability, the same experiments were conducted with a peer 
network double the size of the first one. The input transactions for these experiments can be found [here](transactions).

An HTTP REST API was created in order to interact with the 
blockchain. In the table below you can see the different 
endpoints:

|Route|Verb|Action|
|-----|----|------|
|/api/transaction/|GET|New Transaction. The `sender_address` wallet will attempt to send X NBC coins to the `recipient_address` wallet.|
|/api/view/|GET|View last Transactions. Returns the transactions included in the last valid block.|
|/api/balance/|GET|Show balance. Returns the balance of the user's wallet.|
|/api/wallets/|GET|Show all balances. Return a `Map[String, Int]]`, showing all users with the balance of their wallet - just for debugging purposes. |
|/api/help/|GET|Explains all actions|

Certain command line arguments are needed when trying to run this application:
* hostname: String = the host that the `ActorSystem` will use for remoting
* port: Int = the port to listen to on the specified host
* isBootstrap: Boolean = whether this peer will be the bootstrap node that all other peers will connect to in order to receive their initial balance
* NETWORK_SIZE: Int = the number of peers - after it is reached, no more peers can join
* CAPACITY: Int = the maximum number of transactions in a block
* DIFFICULTY: Int = the mining difficulty - represents the number of leading zeros the hash of a new block needs to have in order to be added to the blockchain by a miner

A complete explanation of how Noobcash works can be found [here](NOOBCASH_FUNCTION.md).

***
## License
Copyright &copy; 2020 Kyriakos Tsaliagkos

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.