package de.leechuck.aberiori;
/* This file is copyright (c) 2008-2013 Philippe Fournier-Viger
 * 
 * This file is part of the SPMF DATA MINING SOFTWARE
 * (http://www.philippe-fournier-viger.com/spmf).
 * 
 * SPMF is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 * 
 * SPMF is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License along with
 * SPMF. If not, see <http://www.gnu.org/licenses/>.
 */
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.semanticweb.owlapi.apibinding.OWLManager
import org.semanticweb.owlapi.model.*
import org.semanticweb.owlapi.reasoner.*
import org.semanticweb.owlapi.profiles.*
import org.semanticweb.owlapi.util.*
import org.semanticweb.owlapi.io.*
import org.semanticweb.elk.owlapi.*
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary


import ca.pfv.spmf.patterns.itemset_array_integers_with_count.Itemset;
import ca.pfv.spmf.patterns.itemset_array_integers_with_count.Itemsets;
import ca.pfv.spmf.tools.MemoryLogger;

/**
 * This is an optimized implementation of the Apriori algorithm that uses binary search to
 * check if subsets of a candidate are frequent and other optimizations.
 *  <br/><br/>
 *  
 * The apriori algorithm is described  in :
 * <br/><br/>
 *  
 * Agrawal R, Srikant R. "Fast Algorithms for Mining Association Rules", VLDB.
 * Sep 12-15 1994, Chile, 487-99,
 * <br/><br/>
 *  
 * The Apriori algorithm finds all the frequents itemsets and their support in a
 * transaction database provided by the user.
 * <br/><br/>
 *  
 * This is an optimized version that saves the result to a file
 * or keep it into memory if no output path is provided
 * by the user to the runAlgorithm() method.
 * 
 * @see Itemset
 * @see Itemsets
 * @author Philippe Fournier-Viger
 */
public class Aberiori {

  // the current level k in the breadth-first search
  protected int k; 

  // variables for statistics
  protected int totalCandidateCount = 0; // number of candidate generated during last execution
  protected long startTimestamp; // start time of last execution
  protected long endTimestamp; // end time of last execution
  private int itemsetCount;  // itemset found during last execution
  private int databaseSize;
	
  // the minimum support set by the user
  private int minsupRelative;
	
  // A memory representation of the database.
  // Each position in the list represents a transaction
  private List<int[]> database = null;
	
  // The  patterns that are found 
  // (if the user want to keep them into memory)
  protected Itemsets patterns = null;

  // object to write the output file (if the user wants to write to a file)
  BufferedWriter writer = null; 

  OWLOntologyManager manager = OWLManager.createOWLOntologyManager()

  OWLDataFactory factory = manager.getOWLDataFactory()

  // A map of an ontology class URI to a number
  Map<OWLClass, Integer> class2int = [:]

  // A map of a int to ontology class URI
  Map<Integer, String> int2class = [:]

  // A map of the dependencies: class2superclass
  Map<String, Set<String>> class2super = [:].withDefault { new LinkedHashSet<String>() }

  /**
   * Default constructor
   */
  public Aberiori(String ontfile) {

    OWLOntology ont = manager.loadOntologyFromOntologyDocument(new File(ontfile))
    
    int count = 0
    ont.getClassesInSignature(true).each { cla ->
      def cl = cla.toString()
      class2int[cl] = count
      int2class[count] = cl
      count += 1
    }

    ConsoleProgressMonitor progressMonitor = new ConsoleProgressMonitor()
    OWLReasonerConfiguration config = new SimpleConfiguration(progressMonitor)

    OWLReasonerFactory rf = new ElkReasonerFactory()
    OWLReasoner reasoner = rf.createReasoner(ont,config)
    reasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY)
    
    ont.getClassesInSignature(true).each { cl ->
      def sup = reasoner.getSuperClasses(cl, false).collect { it.getEntities() }.flatten()
      sup.each { class2super[cl.toString()].add(it.toString()) }
    }
  }

  /**
   * Method to run the algorithm
   * @param minsup  a minimum support value as a percentage
   * @param input  the path of an input file
   * @param output the path of an input if the result should be saved to a file. If null,
   *               the result will be kept into memory and this
   *               method will return the result.
   * @throws IOException exception if error while writting or reading the input/output file
   */
  public Itemsets runAlgorithm(double minsup, String input, String output) throws IOException {
		
    // if the user want to keep the result into memory
    if(output == null){
      writer = null;
      patterns =  new Itemsets("FREQUENT ITEMSETS");
    }else{ // if the user want to save the result to a file
      patterns = null;
      writer = new BufferedWriter(new FileWriter(output)); 
    }
		
    // record the start time
    startTimestamp = System.currentTimeMillis();
		
    // set the number of itemset found to zero
    itemsetCount = 0;
    // set the number of candidate found to zero
    totalCandidateCount = 0;
    // reset the utility for checking the memory usage
    MemoryLogger.getInstance().reset();

    // READ THE INPUT FILE
    // variable to count the number of transactions
    databaseSize = 0;
    // Map to count the support of each item
    // Key: item  Value : support
    Map<Integer, Integer> mapItemCount = new HashMap<Integer, Integer>(); // to count the support of each item
		
    database = new ArrayList<int[]>(); // the database in memory (intially empty)
		
    // scan the database to load it into memory and count the support of each single item at the same time
    BufferedReader reader = new BufferedReader(new FileReader(input));
    String line;
    // for each line (transactions) until the end of the file
    while (((line = reader.readLine()) != null)) { 
      
      // ABERIORI: Input must not have comments

      // if the line is  a comment, is  empty or is a
      // kind of metadata
      /*      if (line.isEmpty() == true ||
	  line.charAt(0) == '#' || line.charAt(0) == '%'
	  || line.charAt(0) == '@') {
	continue;
	}*/

      // split the line according to spaces
      String[] lineSplited = line.split(" ");

      Set<String> transSet = new HashSet<String>()
      lineSplited.each { uri ->
	uri = uri.trim()
	transSet.add(uri)
	class2super[uri].each { transSet.add(it) }
      }
      
      def transSetNum = transSet.collect { class2int[it] }
      transSetNum.removeAll { it == null }
      // create an array of int to store the items in this transaction
      Integer[] transaction = new Integer[transSetNum.size()];
			
      // for each item in this line (transaction)
      for (int i=0; i< transSetNum.size(); i++) {
	def item = transSetNum[i]
	transaction[i] = item
	Integer count = mapItemCount.get(item);
	if (count == null) {
	  mapItemCount.put(item, 1);
	} else {
	  mapItemCount.put(item, ++count);
	}
      }
      Arrays.sort(transaction)
      // add the transaction to the database
      database.add(transaction);
      // increase the number of transaction
      databaseSize++;
    }
    // close the input file
    reader.close();
    // conver the minimum support as a percentage to a 
    // relative minimum support as an integer
    this.minsupRelative = (int) Math.ceil(minsup * databaseSize);
		
    // we start looking for itemset of size 1
    k = 1;
		
    // We add all frequent items to the set of candidate of size 1
    List<Integer> frequent1 = new ArrayList<Integer>();
    for(Entry<Integer, Integer> entry : mapItemCount.entrySet()){
      if(entry.getValue() >= minsupRelative){
	frequent1.add(entry.getKey());
	saveItemsetToFile(entry.getKey(), entry.getValue());
      }
    }
    mapItemCount = null;
		
    // We sort the list of candidates by lexical order
    // (Apriori need to use a total order otherwise it does not work)
    Collections.sort(frequent1, new Comparator<Integer>() {
		       public int compare(Integer o1, Integer o2) {
			 return o1 - o2;
		       }
		     });
		
    // If no frequent item, the algorithm stops!
    if(frequent1.size() == 0){
      // close the output file if we used it
      if(writer != null){
	writer.close();
      }
      return patterns; 
    }
		
    // add the frequent items of size 1 to the total number of candidates
    totalCandidateCount += frequent1.size();
		
		
    // Now we will perform a loop to find all frequent itemsets of size > 1
    // starting from size k = 2.
    // The loop will stop when no candidates can be generated.
    List<Itemset> level = null;
    k = 2;
    while(level == null || level.isEmpty() == false) {
      // we check the memory usage
      MemoryLogger.getInstance().checkMemory();
			
      // Generate candidates of size K
      List<Itemset> candidatesK;
			
      // if we are at level k=2, we use an optimization to generate candidates
      if(k ==2 ) {
	candidatesK = generateCandidate2(frequent1);
      }else{
	// otherwise we use the regular way to generate candidates
	candidatesK = generateCandidateSizeK(level);
      }
			
      // we add the number of candidates generated to the total
      totalCandidateCount += candidatesK.size();

      // We scan the database one time to calculate the support
      // of each candidates and keep those with higher suport.
      // For each transaction:
      for(int[] transaction: database){
	// NEW OPTIMIZATION 2013: Skip transactions shorter than k!
	if(transaction.length < k) {
	  //					System.out.println("test");
	  continue;
	}
	// END OF NEW OPTIMIZATION
				
	// for each candidate:
      loopCand:	for(Itemset candidate : candidatesK){
	  // a variable that will be use to check if 
	  // all items of candidate are in this transaction
	  int pos = 0;
	  // for each item in this transaction
	  for(int item: transaction){
	    // if the item correspond to the current item of candidate
	    if(item == candidate.itemset[pos]){
	      // we will try to find the next item of candidate next
	      pos++;
	      // if we found all items of candidate in this transaction
	      if(pos == candidate.itemset.length){
		// we increase the support of this candidate
		candidate.support++;
		continue loopCand;
	      }
	      // Because of lexical order, we don't need to 
	      // continue scanning the transaction if the current item
	      // is larger than the one that we search  in candidate.
	    }else if(item > candidate.itemset[pos]){
	      continue loopCand;
	    }
						
	  }
	}
      }

      // We build the level k+1 with all the candidates that have
      // a support higher than the minsup threshold.
      level = new ArrayList<Itemset>();
      for (Itemset candidate : candidatesK) {
	// if the support is > minsup
	if (candidate.getAbsoluteSupport() >= minsupRelative) {
	  // add the candidate
	  level.add(candidate);
	  // the itemset is frequent so save it into results
	  saveItemset(candidate);
	}
      }
      // we will generate larger itemsets next.
      k++;
    } 

    // record end time
    endTimestamp = System.currentTimeMillis();
    // check the memory usage
    MemoryLogger.getInstance().checkMemory();

    // close the output file if the result was saved to a file.
    if(writer != null){
      writer.close();
    }
		
    return patterns;
  }

  /**
   * Return the number of transactions in the last database read by the algorithm.
   * @return the number of transactions.
   */
  public int getDatabaseSize() {
    return databaseSize;
  }

  // /**
  //  * This method generates candidates itemsets of size 2 based on
  //  * itemsets of size 1.
  //  * @param frequent1  the list of frequent itemsets of size 1.
  //  * @return a List of Itemset that are the candidates of size 2.
  //  */
  private List<Itemset> generateCandidate2(List<Integer> frequent1) {
    List<Itemset> candidates = new ArrayList<Itemset>();
		
    // For each itemset I1 and I2 of level k-1
    for (int i = 0; i < frequent1.size(); i++) {
      Integer item1 = frequent1.get(i);
      for (int j = i + 1; j < frequent1.size(); j++) {
  	Integer item2 = frequent1.get(j);

	Integer[] temp = new Integer[2]
	temp[0] = item1
	temp[1] = item2
  	// Create a new candidate by combining itemset1 and itemset2
  	candidates.add(new Itemset(temp));
      }
    }
    return candidates;
  }

  /**
   * Method to generate itemsets of size k from frequent itemsets of size K-1.
   * @param levelK_1  frequent itemsets of size k-1
   * @return itemsets of size k
   */
  protected List<Itemset> generateCandidateSizeK(List<Itemset> levelK_1) {
    // create a variable to store candidates
    List<Itemset> candidates = new ArrayList<Itemset>();

    // For each itemset I1 and I2 of level k-1
  loop1: for (int i = 0; i < levelK_1.size(); i++) {
      Integer [] itemset1 = levelK_1.get(i).itemset;
    loop2: for (int j = i + 1; j < levelK_1.size(); j++) {
	Integer [] itemset2 = levelK_1.get(j).itemset;

	// we compare items of itemset1 and itemset2.
	// If they have all the same k-1 items and the last item of
	// itemset1 is smaller than
	// the last item of itemset2, we will combine them to generate a
	// candidate
	for (int k = 0; k < itemset1.length; k++) {
	  // if they are the last items
	  if (k == itemset1.length - 1) {
	    // the one from itemset1 should be smaller (lexical
	    // order)
	    // and different from the one of itemset2
	    if (itemset1[k] >= itemset2[k]) {
	      continue loop1;
	    }
	  }
	  // if they are not the last items, and
	  else if (itemset1[k] < itemset2[k]) {
	    continue loop2; // we continue searching
	  } else if (itemset1[k] > itemset2[k]) {
	    continue loop1; // we stop searching: because of lexical
	    // order
	  }
	}

	// Create a new candidate by combining itemset1 and itemset2
	Integer[] newItemset = new Integer[itemset1.length+1];
	System.arraycopy(itemset1, 0, newItemset, 0, itemset1.length);
	newItemset[itemset1.length] = itemset2[itemset2.length -1];

	// test is the new item (itemset2[itemset2.length-1]) is a superclass of an element in itemset1
	def itemset1str = itemset1.collect { int2class[it] }
	def newItem = int2class[itemset2[itemset2.length-1]]

	Boolean isEligible = true
	itemset1str.each { cl ->
	  if (newItem in class2super[cl]) {
	    isEligible = false
	  }
	}

	// only continue if the new itemset is eligible
	if (isEligible) {
	  // The candidate is tested to see if its subsets of size k-1 are
	  // included in
	  // level k-1 (they are frequent).
	  if (allSubsetsOfSizeK_1AreFrequent(newItemset, levelK_1)) {
	    candidates.add(new Itemset(newItemset));
	  }
	}
      }
    }
    return candidates; // return the set of candidates
  }

  /**
   * Method to check if all the subsets of size k-1 of a candidate of size k are freuqnet
   * @param candidate a candidate itemset of size k
   * @param levelK_1  the frequent itemsets of size k-1
   * @return true if all the subsets are frequet
   */
  protected boolean allSubsetsOfSizeK_1AreFrequent(Integer[] candidate, List<Itemset> levelK_1) {
    // generate all subsets by always each item from the candidate, one by one
    for(int posRemoved=0; posRemoved< candidate.length; posRemoved++){

      // perform a binary search to check if  the subset appears in  level k-1.
      int first = 0;
      int last = levelK_1.size() - 1;
	       
      // variable to remember if we found the subset
      boolean found = false;
      // the binary search
      while( first <= last )
      {
	int middle = ( first + last ) / 2;

	if(sameAs(levelK_1.get(middle), candidate, posRemoved)  < 0 ){
	  first = middle + 1;  //  the itemset compared is larger than the subset according to the lexical order
	}
	else if(sameAs(levelK_1.get(middle), candidate, posRemoved)  > 0 ){
	  last = middle - 1; //  the itemset compared is smaller than the subset  is smaller according to the lexical order
	}
	else{
	  found = true; //  we have found it so we stop
	  break;
	}
      }

      if(found == false){  // if we did not find it, that means that candidate is not a frequent itemset because
	// at least one of its subsets does not appear in level k-1.
	return false;
      }
    }
    return true;
  }

	
  /**
   * Method to compare two itemsets and see if they are the same
   * @param itemset an itemset
   * @param candidate a candidate itemset
   * @param posRemoved  the position of an item that should be removed from "candidate" to perform the comparison.
   * @return 0 if they are the same, 1 if itemset is larger according to lexical order,
   *         -1 if smaller.
   */
  private int sameAs(Itemset itemset, Integer [] candidate, int posRemoved) {
    // a variable to know which item from candidate we are currently searching
    int j=0;
    // loop on items from "itemset"
    for(int i=0; i<itemset.itemset.length; i++){
      // if it is the item that we should ignore, we skip it
      if(j == posRemoved){
	j++;
      }
      // if we found the item j, we will search the next one
      if(itemset.itemset[i] == candidate[j]){
	j++;
	// if  the current item from i is larger than j, 
	// it means that "itemset" is larger according to lexical order
	// so we return 1
      }else if (itemset.itemset[i] > candidate[j]){
	return 1;
      }else{
	// otherwise "itemset" is smaller so we return -1.
	return -1;
      }
    }
    return 0;
  }
	

  void saveItemset(Itemset itemset) throws IOException {
    itemsetCount++;
		
    // if the result should be saved to a file
    if(writer != null){
      writer.write(itemset.toString() + " #SUP: "
		   + itemset.getAbsoluteSupport());
      writer.newLine();
    }// otherwise the result is kept into memory
    else{
      patterns.addItemset(itemset, itemset.size());
    }
  }
	
  void saveItemsetToFile(Integer item, Integer support) throws IOException {
    itemsetCount++;
		
    // if the result should be saved to a file
    if(writer != null){
      writer.write(item + " #SUP: " + support);
      writer.newLine();
    }// otherwise the result is kept into memory
    else{
      Itemset itemset = new Itemset(item);
      itemset.setAbsoluteSupport(support);
      patterns.addItemset(itemset, 1);
    }
  }
	
  /**
   * Print statistics about the algorithm execution to System.out.
   */
  public void printStats() {
    System.out.println("=============  APRIORI - STATS =============");
    System.out.println(" Candidates count : " + totalCandidateCount);
    System.out.println(" The algorithm stopped at size " + (k - 1)
		       + ", because there is no candidate");
    System.out.println(" Frequent itemsets count : " + itemsetCount);
    System.out.println(" Maximum memory usage : " + MemoryLogger.getInstance().getMaxMemory() + " mb");
    System.out.println(" Total time ~ " + (endTimestamp - startTimestamp) + " ms");
    System.out.println("===================================================");
  }
}
