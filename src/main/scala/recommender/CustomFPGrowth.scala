package recommender

import java.{util => ju}

import org.apache.spark.mllib.fpm.AssociationRules.Rule

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.reflect.ClassTag
import org.apache.spark.{HashPartitioner,Partitioner, SparkException}
import org.apache.log4j.LogManager
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel
import org.apache.spark.mllib.fpm.FPGrowthModel
import org.apache.spark.mllib.fpm.FPGrowth._

import scala.collection.mutable.ListBuffer


class CustomFPGrowth(private var minSupport: Double,
                     private var numPartitions: Int,
                     private var adaptive: mutable.Map[String, Int]     ) extends LogManager with Serializable {

  def this() = this(0.3, -1, scala.collection.mutable.Map[String,Int]())


  def setMinSupport(minSupport: Double): this.type = {
    require(minSupport >= 0.0 && minSupport <= 1.0,
      s"Minimal support level must be in range [0, 1] but got $minSupport")
    this.minSupport = minSupport
    this
  }


  def setNumPartitions(numPartitions: Int): this.type = {
    require(numPartitions > 0,
      s"""Number of partitions must be positive but got $numPartitions""")
    this.numPartitions = numPartitions
    this
  }

  def setAdaptiveMap(adaptive: mutable.Map[String, Int]) : this.type = {
    //require(adaptive.nonEmpty, "Multiple minimum support calculation failed.")
    this.adaptive = adaptive
    this
  }


  def run[Item: ClassTag](data: RDD[Array[Item]]): FPGrowthModel[Item] = {
    if (data.getStorageLevel == StorageLevel.NONE) {  // Livello del disco in cui viene caricato il dataset
      LogManager.getRootLogger.warn("Input data is not cached.")
    }
    // Numero di transazioni
    val count = data.count()
    // Calcola il minimo numero di occorrenze per rispettare la soglia, per difetto
    val minCount = math.ceil(minSupport * count).toLong
    // Se non imposti automaticamente numPartitions, importa numParts come numero di cores della macchina
    val numParts = if (numPartitions > 0) numPartitions else data.partitions.length
    // Definisce un HashPartitioner che trova il nodo di computazione per ogni partizione in base alla funzione hash % numParts
    val partitioner = new HashPartitioner(numParts)
    // Ottiene gli item frequenti a partire dai dati e dalle loro partizioni
    val freqItems = genFreqItems(data, minCount, partitioner)
    // Ottiene gli itemset frequenti a partire dai freqItems
    val freqItemsets = genFreqItemsets(data, minCount, freqItems, partitioner)
    //Risultato finale
    new FPGrowthModel(freqItemsets)
  }

  /**
    * Generates frequent items by filtering the input data using minimal support level.
    * @param minCount minimum count for frequent itemsets
    * @param partitioner partitioner used to distribute items
    * @return array of frequent pattern ordered by their frequencies
    */
  private def genFreqItems[Item: ClassTag](
                                            data: RDD[Array[Item]],
                                            minCount: Long,
                                            partitioner: Partitioner): Array[Item] = {
    data.flatMap { t =>
      val uniq = t.toSet
      if (t.length != uniq.size) {
        throw new SparkException(s"Items in a transaction must be unique but got ${t.toSeq}.")
      }
      t
    }.map(v => (v, adaptive.getOrElse(v.toString, 1)))  // Associa ad ogni item della transazione il valore 1 //Precedente: secondo parametro 1L
      .reduceByKey(partitioner, _ + _)  // Somma aggregando per chiave
      .filter(_._2 >= minCount)         // Filtra sulla soglia di occorrenze minima
      .collect()                        // Restituisce l'HashMap
      .sortBy(-_._2)                    // - ordine decrescente, _._2 considerando il numero di occorrenze
      .map(_._1)                        // Ritorna una rdd contenente solo gli id degli item (ordinati)
  }

  /**
    * Generate frequent itemsets by building FP-Trees, the extraction is done on each partition.
    * @param data transactions
    * @param minCount minimum count for frequent itemsets
    * @param freqItems frequent items
    * @param partitioner partitioner used to distribute transactions
    * @return an RDD of (frequent itemset, count)
    */
  private def genFreqItemsets[Item: ClassTag](
                                               data: RDD[Array[Item]],
                                               minCount: Long,
                                               freqItems: Array[Item],
                                               partitioner: Partitioner): RDD[FreqItemset[Item]] = {
    val itemToRank = freqItems.zipWithIndex.toMap   //Associa un indice ad ogni item, crea una ranking list dove in prima posizione c'è l'item che aveva più occorrenze
    val x = data.flatMap { transaction =>
      genCondTransactions(transaction, itemToRank, partitioner)
    }
    /** x contiene la lista delle transazioni condizionate su un singolo item */

     val y = x.aggregateByKey(new FPTree[Int](adaptive), partitioner.numPartitions)(
      (tree, transaction) => tree.add(transaction, 1L), // Combiner logic, aggiungo uno per ogni item processato
      (tree1, tree2) => tree1.merge(tree2))
    /** AggregateByKey costruisce effettivamente l'FPTree Aggiungendo ogni transazione ad esso e
      * unendo all'albero tutte le transazioni dei nuovi alberi generati, tutto per ogni partitioner */

    y.flatMap { case (part, tree) =>
        tree.extractMMS(minCount, freqItems, x => partitioner.getPartition(x) == part)
      }.map { case (ranks, count) =>
      //println("ranks: " + ranks.map(i=>freqItems(i)).toArray.mkString(",") + ", count: " + count)
      new FreqItemset(ranks.map(i => freqItems(i)).toArray, count)
    }
  }

  /**
    * Generates conditional transactions.
    * @param transaction a transaction
    * @param itemToRank map from item to their rank
    * @param partitioner partitioner used to distribute transactions
    * @return a map of (target partition, conditional transaction)
    */
  private def genCondTransactions[Item: ClassTag](
                                                   transaction: Array[Item],
                                                   itemToRank: Map[Item, Int],
                                                   partitioner: Partitioner): mutable.Map[Int, Array[Int]] = {
    val output = mutable.Map.empty[Int, Array[Int]]   //Il map conterrà coppie dove ad ogni transazione condizionale è associata una partizione
    // Filter the basket by frequent items pattern and sort their ranks.
    val filtered = transaction.flatMap(itemToRank.get)    //Contiene le posizioni degli item della transazione nella ranking list
    ju.Arrays.sort(filtered)  //Ordina la lista dei di rank della transazione in ordine crescente
    val n = filtered.length
    //println("n items: " + n)
    var i = n - 1
    while (i >= 0) {
      val item = filtered(i)
      //println("rank: " + item)
      val part = partitioner.getPartition(item)
      //println("part:" + part)

      if (!output.contains(part)) {
        output(part) = filtered.slice(0, i + 1)
        //("output: ")
        //output.foreach(println)
      }
      i -= 1
    }
    output
  }
}


object FPGrowth {

  /**
    * Frequent itemset.
    * @param items items in this itemset. Java users should call `FreqItemset.javaItems` instead.
    * @param freq frequency
    * @tparam Item item type
    *
    */
  class FreqItemset[Item] (
    val items: Array[Item],
    val freq: Long) extends Serializable {


    def javaItems: java.util.List[Item] = {
      items.toList.asJava
    }

    override def toString: String = {
      s"${items.mkString("{", ",", "}")}: $freq"
    }
  }
}



/**
  * FP-Tree data structure used in FP-Growth.
  * @tparam T item type
  */
class FPTree[T](adaptive: mutable.Map[String, Int]) extends Serializable {

  class Node[T](val parent: Node[T]) extends Serializable {
    var item: T = _   //Identificatore dell'Item
    var count: Long = 0L    //
    val children: mutable.Map[T, Node[T]] = mutable.Map.empty

    def isRoot: Boolean = parent == null

    override def toString: String = "item: " + item.toString + ", count: " + count.toString
  }

  /** Summary of an item in an FP-Tree. */
  private class Summary[T] extends Serializable {
    var count: Long = 0L
    val nodes: ListBuffer[Node[T]] = ListBuffer.empty

    override def toString: String = nodes.toString()
  }

  val root: Node[T] = new Node(null)

  private val summaries: mutable.Map[T, Summary[T]] = mutable.Map.empty

  /** Adds a transaction with count. */
  def add(t: Iterable[T], count: Long = 1L): this.type = {
    require(count > 0)  // Verifico che la variabile count sia positiva
    var curr = root     // Parto dalla root dell'albero
    curr.count += count // La count della radice segna il numero di transazioni proccessate dall'albero
    // Ciclo per ogni item della transazione
    t.foreach { item =>
      // Ottengo il summary dell'item considerato per poi aumentarne le occorrenze, altrimenti creo un nuovo summary
      val summary = summaries.getOrElseUpdate(item, new Summary)
      summary.count += count
      // Ottengo i figli del nodo corrente (partendo dalla radice) e verifico la presenza del mio item
      val child = curr.children.getOrElseUpdate(item, {
        // Se il mio item non è nell'albero lo creo e lo aggiungo
        val newNode = new Node(curr)
        newNode.item = item
        summary.nodes += newNode
        newNode
      })
      // Aggiungo la conta del child a prescindere se l'ho preso o l'ho appena creato
      child.count += count
      curr = child
      // D'ora in poi nel foreach considererò l'item attuale come corrente
    }
    this  // Alla fine ritorno l'intero FP-Tree modificato
  }

  /** Adds a transaction with count. */
  def addMMS(t: Iterable[T], count: Long = 1L, adaptive : mutable.Map[String,Int]): this.type = {
    require(count > 0)  // Verifico che la variabile count sia positiva
    var curr = root     // Parto dalla root dell'albero
    curr.count += count
    //curr.count += count // La count della radice segna il numero di transazioni proccessate dall'albero
    // Ciclo per ogni item della transazione
    t.foreach { item =>
      // Ottengo il summary dell'item considerato per poi aumentarne le occorrenze, altrimenti creo un nuovo summary
      val summary = summaries.getOrElseUpdate(item, new Summary)
      //
      // summary.count += adaptive.getOrElse(, 1)
      //summary.count += count
      // Ottengo i figli del nodo corrente (partendo dalla radice) e verifico la presenza del mio item
      val child = curr.children.getOrElseUpdate(item, {
        // Se il mio item non è nell'albero lo creo e lo aggiungo
        val newNode = new Node(curr)
        newNode.item = item
        summary.nodes += newNode
        newNode
      })
      // Aggiungo la conta del child a prescindere se l'ho preso o l'ho appena creato
      child.count += adaptive.getOrElse(item.toString, 1)
      //child.count += count
      curr = child
      // D'ora in poi nel foreach considererò l'item attuale come corrente
    }
    this  // Alla fine ritorno l'intero FP-Tree modificato
  }

  /** Merges another FP-Tree. */
  def merge(other: FPTree[T]): this.type = {
    other.transactions.foreach { case (t, c) =>
      //println("Transazioni che unisco al tree: " + other.transactions.foreach(println))
      add(t, c)
    }
    this
  }

  /** Merges another FP-Tree. */
  def mergeMMS(other: FPTree[T], adaptive : mutable.Map[String, Int]): this.type = {
    other.transactions.foreach { case (t, c) =>
      //println("Transazioni che unisco al tree: " + other.transactions.foreach(println))
      addMMS(t, c, adaptive)
    }
    this
  }

  /** Gets a subtree with the suffix. */
  private def project(suffix: T): FPTree[T] = {
    val tree = new FPTree[T](adaptive)
    if (summaries.contains(suffix)) {
      val summary = summaries(suffix)
      summary.nodes.foreach { node =>
        var t = List.empty[T]
        var curr = node.parent
        while (!curr.isRoot) {
          t = curr.item :: t
          curr = curr.parent
        }
        tree.add(t, node.count)
      }
    }
    tree
  }

  /** Returns all transactions in an iterator. */
  def transactions: Iterator[(List[T], Long)] = getTransactions(root)

  /** Returns all transactions under this node. */
  private def getTransactions(node: Node[T]): Iterator[(List[T], Long)] = {
    var count = node.count
    node.children.iterator.flatMap { case (item, child) =>
      //println("item: " + item + "    child: " + child)
      getTransactions(child).map { case (t, c) =>
        count -= c
        //println("t: " + t + "    c: " + c + "    count: " + count)
        (item :: t, c)
      }
    } ++ {
      if (count > 0) {
        Iterator.single((Nil, count))
      } else {
        Iterator.empty
      }
    }
  }

  /** Extracts all patterns with valid suffix and minimum count. */
  def extract[Item: ClassTag](
               minCount: Long,
               validateSuffix: T => Boolean = _ => true): Iterator[(List[T], Long)] = {
    summaries.iterator.flatMap { case (item, summary) =>
      if (validateSuffix(item) && summary.count >= minCount) {
        //println("ranks: " + ranks.map(i=>freqItems(i)).toArray.mkString(",") + ", count: " + count)
        //println(summary.nodes.map(i => freqItems(i.item.toString.toInt)).toArray.mkString(","))
        Iterator.single((item :: Nil, summary.count)) ++
          project(item).extract(minCount).map { case (t, c) =>
            (item :: t, c)
          }
      } else {
        Iterator.empty
      }
    }
  }

  def extractMMS[Item: ClassTag](
                               minCount: Long, freqItems: Array[Item],
                               validateSuffix: T => Boolean = _ => true): Iterator[(List[T], Long)] = {
    summaries.iterator.flatMap { case (item, summary) =>
      if (validateSuffix(item) && getMultiplier(freqItems, summary) * summary.count >= minCount) {
        //println("prezzo: " +getMinimumPrice(freqItems, summary) + "conta: " +  summary.count)
        Iterator.single((item :: Nil, summary.count)) ++
          project(item).extractMMS(minCount, freqItems).map { case (t, c) =>
            (item :: t, c)
          }
      } else {
        Iterator.empty
      }
    }
  }

  def getMultiplier[Item: ClassTag](freqItems: Array[Item], summary: Summary[T]): Int ={
    val items = summary.nodes.map(i => freqItems(i.item.toString.toInt)).toArray
    var truth : mutable.Map[String, Int] = mutable.Map[String, Int]()
    for(e <- items) yield {
      truth += (e.toString -> adaptive.getOrElse(e.toString, 1))
    }

    var res = 0
    if ((truth.maxBy(_._2)_2) <= 1){
      res = 1
    }
    else if( (truth.maxBy(_._2)_2) >= 1 && (truth.maxBy(_._2)_2) < 5){
      res = 2
    }
    else if((truth.maxBy(_._2)_2) >= 5 && (truth.maxBy(_._2)_2) < 10){
      res = 3
    }
    else if((truth.maxBy(_._2)_2) >= 10 && (truth.maxBy(_._2)_2) < 100){
      res = 4
    }
    else{
      res = 5
    }
    res
  }
}

