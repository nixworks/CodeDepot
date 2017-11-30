/**
* Copyright (c) 2009 SRA (Software Research Associates, Inc.)
*
* This file is part of CodeDepot.
* CodeDepot is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License version 3.0
* as published by the Free Software Foundation and appearing in
* the file GPL.txt included in the packaging of this file.
*
* CodeDepot is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with CodeDepot. If not, see <http://www.gnu.org/licenses/>.
*
**/
package jp.co.sra.codedepot.search;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.Map.Entry;

import jp.co.sra.codedepot.solr.Indexer;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.payloads.PayloadHelper;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Weight;
import org.apache.lucene.search.spans.SpanQuery;
import org.apache.lucene.search.spans.Spans;

import org.apache.solr.common.util.NamedList;
import org.apache.solr.search.DocIterator;
import org.apache.solr.search.DocList;
import org.apache.solr.search.DocListAndSet;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.search.SolrIndexSearcher.QueryCommand;
import org.apache.solr.search.SolrIndexSearcher.QueryResult;
import org.apache.solr.util.SimpleOrderedMap;

import org.slf4j.LoggerFactory;

/**
 * the main class that does the clone search
 * @author ye
 * @version $Id: CloneSearcher.java 2342 2017-11-09 05:36:32Z fang $
 * @depracated use CloneSearcherSolr instead
 */
@Deprecated
public class CloneSearcher {

	private static org.slf4j.Logger logger = LoggerFactory.getLogger(CloneSearcher.class);

	// private CloneMatchDocCollector _matchDocCollector;
	private NamedList<CloneMatchDoc> _matchDocs;

	private int _dslop = 0; //allowed mismatch tokens in document
	private int _qslop = 0; //allowed mismatch tokens in query

	private int _start = 0; //starting offset
	private int _rows = 10; //number of returned docs

	private SolrIndexSearcher _searcher;
		// using SolrIndexSearcher can speed up the process of getting
	    // highlights because SolrIndexSearcher pre-loads specified fields
	//private IndexSearcher _searcher;

	private IndexReader _reader;
	private Analyzer _analyzer;
	private String _fieldName;
	private int _paddingLineNo = 0; // not used yet, number of lines to surround the results

	private SpanQuery _query;
	private Weight _weight;  //for debug
	private Scorer _scorer;  //for debug

	//DocSet is not needed if we stick with One-Phase query
	private List<DocListAndSet> _result; //FIXME No need to use list for one case
		//holds the search results, ordered by non-slop, slopped
	//private int _totalMatches; //num or matched docs

	//fields that are needed for generating highlights
	private static final Set<String> _fieldSet  = new HashSet<String>(3);
	static {
		_fieldSet.add(Indexer.ID);
		_fieldSet.add(Indexer.SRC);
		_fieldSet.add(Indexer.BEGIN);
	}

	public CloneSearcher(SolrIndexSearcher searcher, IndexReader reader,
			Analyzer analyzer, String fieldName, int dslop, int qslop) {
		logger.debug("searcher creation");
		_searcher = searcher;
		_analyzer = analyzer;
		_fieldName = fieldName;
		_qslop = dslop;
		_dslop = dslop;
		_reader = reader;
	}

	public CloneSearcher(SolrIndexSearcher searcher, Analyzer analyzer) {
		this(searcher, searcher.getReader(), analyzer, Indexer.CLONETKN, 0, 0);
	}

	public CloneSearcher(SolrIndexSearcher searcher, Analyzer analyzer, int dslop, int qslop) {
		this(searcher, searcher.getReader(), analyzer, Indexer.CLONETKN, dslop, qslop);
	}

	/**
	 * Retrieve documents that match the text (code fragments) specified by <code>qStr</code>,
	 * and that are not included in <code>filter</code>.
	 * Matching documents are stored in <code>_matchDocCollector</code>. Currently, this collector
	 * is not sorted yet.
	 *
	 * @param qStr: code fragments to be matched against
	 * @param filter: documents that should not be returned.
	 * @param dslop: number of statements in the retrieved documents that are not contained in the query
	 * @param qslop: number of statements in the query that are not contained in the returned documents
	 * @return the number of documents that match.
	 * @throws IOException
	 */
	public void search(String qStr, List<Query> filterQueries, int dslop, int qslop, int start, int rows) throws IOException {
		//SpanNearQuery origQuery;
		long stime;

		_result = new ArrayList<DocListAndSet>();
		_start = start;
		_rows = rows;

		//APPROACHE 1: One-Phase Query
		//create a spanORquery with both original and fuzzy queries
		SpanQuery newQuery = SpanQueryUtils.createAllowMismatchSpanQuery(qStr, _analyzer, _fieldName, dslop, qslop);
		stime = System.currentTimeMillis();
		QueryCommand qCmd1 = new QueryCommand();
		qCmd1.setQuery(newQuery)
				//.setNeedDocSet(true)   //no need, if the result not used as a filter
				.setOffset(_start)
				.setLen(_rows)
				.setFlags(SolrIndexSearcher.GET_SCORES)
				.setFilterList(filterQueries);
		QueryResult qRes1 = new QueryResult();

		_searcher.search(qRes1, qCmd1);
		_result.add(qRes1.getDocListAndSet());
		//SpanQueryUtils.printDocListAndSet(qRes3.getDocListAndSet());
		_query = newQuery; //query is needed for get highlights later

		logger.debug("Time for search:{}", (System.currentTimeMillis() - stime));

		//APPROACH 2: Multiple Phase Search (serveral methods are associated with this)
		//This is not much faster than combine all
		//fuzzy query and original query into a single OR query.
		/*
		origQuery = SpanQueryUtils.createSpanNearQuery(qStr, _analyzer, _fieldName, slop);
		stime = System.currentTimeMillis();
		//takes 17212ms for "if(a>b){m=a;}else{m=b;}if(a>b){m=a;}else{m=b;}if(a>b){m=a;}else{m=b;}"
		//without setNeedDocset 16908ms
		SpanQuery newOrQuery = SpanQueryUtils.getLessOneORedSpanQuery(origQuery);
		QueryCommand qCmd2 = new QueryCommand();
		qCmd2.setQuery(newOrQuery)
				//.setNeedDocSet(true)
				//.setOffset(0).setLen(Integer.MAX_VALUE)
				.setOffset(_start).setLen(_rows)
				.setFlags(SolrIndexSearcher.GET_SCORES)
				.setFilterList(filters);
		QueryResult qRes2 = new QueryResult();
		_searcher.search(qRes2, qCmd2);
		_result.add(qRes2.getDocListAndSet());
		_totalMatches = qRes2.getDocListAndSet().docList.matches();
		//SpanQueryUtils.printDocListAndSet(qRes2.getDocListAndSet());
		logger.log(_level, "Slop 1 query search time: " + (System.currentTimeMillis() - stime));

		stime = System.currentTimeMillis();
		//takes 640ms for "if(a>b){m=a;}else{m=b;}if(a>b){m=a;}else{m=b;}if(a>b){m=a;}else{m=b;}"
		//without setNeedDocset 637ms
		qCmd = new QueryCommand();
		qCmd.setQuery(origQuery)
			//.setNeedDocSet(true)  //without this docSet is null
			//.setOffset(0).setLen(Integer.MAX_VALUE) //need to return all
			.setOffset(_start).setLen(_rows)
			.setFlags(SolrIndexSearcher.GET_SCORES)
			.setFilterList(filters); //should limit search to previous result, but not too much improvement
		qRes = new QueryResult();
		_searcher.search(qRes, qCmd);
		//set the result of the first round search
		_result.add(qRes.getDocListAndSet());
		// no need to update _totalMatches, results should have been already included
		//SpanQueryUtils.printDocListAndSet(qRes.getDocListAndSet());
		logger.log(_level, "Orig query search time: " + (System.currentTimeMillis() - stime));
		//_query holds the query used to get spans for highlighting
		//should include all queries that used so far
		if (newOrQuery != null) {
			_query = new SpanOrQuery(new SpanQuery[]{origQuery, newOrQuery});
		} else {
			_query = origQuery;
		}
		*/
	}

	public void search(String qStr, List<Query> filters) throws IOException {
		search(qStr, filters, _dslop, _qslop, 0, 10);
	}

	public void search(String qStr, List<Query> filters, int dslop) throws IOException {
		search(qStr, filters, dslop, _qslop, 0, 10);
	}

	//This is called by CloneSearchHandler
	//return the list of documents to be returned as retrieval result
	public DocList getDocList() {
		if (_result.size() == 1) {
			return _result.get(0).docList;
		} else {
			return null;
			/* code for using multiple phase search
			int[] docs = new int[getReturnDocIds().size()];
			for (int i=0; i<getReturnDocIds().size(); i++) docs[i] = getReturnDocIds().get(i);
			DocSlice ds = new DocSlice(0, docs.length, docs, null, docs.length, _result.get(0).docList.maxScore());
			return ds;
			*/
		}
	}

	/* code for multiple phase search
	public List<Integer> getReturnDocIds() {
		List<Integer> answer = new ArrayList<Integer>(_rows);
		int count = 0;
		for (DocListAndSet s: _result) {
			if (s == null || s.docList == null) continue;
			if (s.docList.size() < _start) {
				continue; //skip all
			}
			DocIterator dIter = s.docList.iterator();
			while (count < _rows && dIter.hasNext()) {
				Integer docid = dIter.nextDoc();
				if (docid < _start) {
					continue;
				}
				answer.add(docid);
				count++;
			}
		}
		return answer;
	}
	*/

	/**
	 * Not USED
	 * Could be used as a filter if we decided to
	 * search from less strict query (with a bigger slop) to
	 * more strict query because later queries must have been
	 * a portion of the previous query.
	 * But is seems now that the first query is quite fast.
	 * @return

	public DocSet getAllDocSet() {
		//return _result.docSet;
		DocSet allDocSet = null;
		Boolean first = true;
		for (DocListAndSet s: _result) {
			if (s == null || s.docSet == null) continue;
			if (first) {
				allDocSet = s.docSet;
				first = false;
			} else {
				allDocSet = allDocSet.union(s.docSet);
			}
		}
		return allDocSet;
	}
	*/

	/**
	 * Returned NamedList is a SimpleOrderedMap(schema.printableUniqueKey(doc) -> cloneSummary)
	 * cloneSummary is an array of NamedList which is also a SimpleOrderedMap(fieldName -> String) with
	 * three fields: "src", "start", and "end"
     * Example of JSON output
     *  "highlighting":{
     *     "/home/.../org/apache/solr/common/SolrException.java#SolrException#ErrorCode(int )":[
	 *                   {src:"private ErrorCode( int c )\n    {\n      <em>code</em> = c;\n    }",
	 *                    start: line-no,
	 *                    end: line-no
	 *                   },
	 *                   {src: ..., start: ..., end: ...}]
     *   }
	 * @return
	 * @throws IOException
	 */
	public NamedList getHighlights() throws CorruptIndexException, IOException {
		long stime = System.currentTimeMillis();

		// major work of finding matching fragments
		fillMatchingSourcesOfReturnedDocs();

		NamedList docSummaries = new SimpleOrderedMap(); //docId --> cloneSummary
		// if use NamedList, returned docSummaries will be treated as a list in JSON
		CloneMatchDoc mdoc;
		Iterator<Map.Entry<String, CloneMatchDoc>> iterator = _matchDocs.iterator();
		while (iterator.hasNext()) {
			Map.Entry<String, CloneMatchDoc> kv = iterator.next();
			mdoc = kv.getValue();
			docSummaries.add(_searcher.doc(mdoc.getDocId()).get(Indexer.ID),
					mdoc.getCloneSummary());
		}

		logger.debug("getHighlights time: {}", (System.currentTimeMillis() - stime));
		return docSummaries;
	}

	/**
	 * return the total number of hits
	 * TODO: anyone needs this? scheduled to delete.
	 */
	/*
	public int getTotalHits() {
		return _result.docList.matches();
		// return _matchDocCollector.getTotalHits();
	}
	*/

	/**
	 * fill the matching source fragments of _returnedDocList
	 * This only fills the matching docs that are to be returned.
	 * i.e. only work with what getDoclist() returns.
	 * @throws IOException
	 */
	private void fillMatchingSourcesOfReturnedDocs() throws IOException {
		long stime = System.currentTimeMillis();

		_matchDocs = new SimpleOrderedMap<CloneMatchDoc>();
		DocList returnDocList = getDocList();
		if (returnDocList.size() == 0) { return; }
		DocIterator iter = returnDocList.iterator();

		CloneMatchDoc mdoc;

		/* code for multiple phase search
		List<Integer> returnDocIds = getReturnDocIds();
		if (returnDocIds == null || returnDocIds.size() == 0) return;
		Iterator<Integer> iter = returnDocIds.iterator();
		*/

		//create a map for documents that are to be returned
		//   _matchDocs is ordered by score, will be returned
		//   orderedDocIds is ordered by docId, used to speeding up spans
		PriorityQueue<Integer> pqDocIds = new PriorityQueue<Integer>(returnDocList.size());
		int i = 0;
		while (iter.hasNext()) {
			int docId = iter.next();
			mdoc = new CloneMatchDoc(docId);
			_matchDocs.add(""+docId, mdoc);
			pqDocIds.add(docId);
		}

		// Update to lucene 2.9.1
		//PayloadSpans s = _query.getPayloadSpans(_reader);
		Spans span = _query.getSpans(_reader);

		Integer currentReturnId = pqDocIds.poll(); //guaranteed one exists
		logger.debug("pqDocIds top is {}", currentReturnId);
		Document doc = null;
		String src = null;
		int previousDocId = -1;
		while ((currentReturnId != null) && span.next()) {
			int spanDocId = span.doc();
			logger.debug("span doc is {}", spanDocId);
			if (spanDocId < currentReturnId) {
				if (!span.skipTo(currentReturnId)) break;
				else { spanDocId = span.doc(); }
			}
			if (spanDocId > currentReturnId) {
				currentReturnId = pqDocIds.poll();
				if (currentReturnId == null) break;  //done with found docs
				logger.debug("skip pass currentReturnId, new currentReturnId={}", currentReturnId);
			}
			if (spanDocId == currentReturnId) {
				//Two paths to this place
				//  (1) span.next().doc() == currentReturnId at the beginning of while
				//  (2) span.skipTo skips pass the initial currentReturnId, and
				//      currentReturnId is advanced to the next, after both advanced,
				//      they have the same number.

				//filling matching sources for documents that are
				//returned for this search only
				mdoc = _matchDocs.get(""+spanDocId);
				if (mdoc != null) {
					if (previousDocId == -1 || spanDocId != previousDocId ) {
						//get the source
						doc = _searcher.doc(spanDocId, _fieldSet);
						// if not solrIndexSearcher, use reader
						// doc = _reader.document(s.doc());
						src = doc.get(Indexer.SRC);
						previousDocId = spanDocId;
					}
					logger.debug("docId is: {}", spanDocId);
					//logger.log("Explanation: " + _weight.explain(_reader, spanDocId));
					int[] fragPosition = getMatchingFragPositions(span, src, doc);
					if (fragPosition != null) {
						int start = fragPosition[0], end = fragPosition[1];

						mdoc.addMatchFrag(src, Integer.parseInt(doc.get(Indexer.BEGIN)), start, end);
					} else {
						logger.error("Should not be here. Failed to get fragment positions.\n" +
								"docId ={}\t _matchDocs={}\n", spanDocId, _matchDocs);
					}

					/*
					if (! mdoc.subsume(start, end)) {
						int lnStart, lnEnd;
						lnStart = Integer.parseInt(doc.get(Indexer.BEGIN)) + StringUtils.countChar(src, '\n', 0, start);
						lnEnd = lnStart + StringUtils.countChar(src, '\n', start, end); //this line is inclusive
						CloneMatchFragment frag = new CloneMatchFragment(src.substring(start, end), lnStart, lnEnd);

						if (frag != null) {
							mdoc.addMatchFrag(frag);
						}
					}
					*/
				} else {
					logger.error("Should not be here. We are looking at a span that are not included in matchDocs."+
							"docId={}\n _matchDocs={}\n", spanDocId, _matchDocs);
				}
			}
		}
		logger.debug("filling time: {}", (System.currentTimeMillis() - stime));
	}

	/**
	 * return the code fragments indicated by the offset information
	 * stored in one payload of the specified document
	 */
	//update to lucene2.9.1
	//private CloneMatchFragment getMatchingSourceFragment(PayloadSpans s, int d) throws IOException {
	private int[] getMatchingFragPositions(Spans s, String src, Document doc) throws IOException {
		if (src == null || s == null || !s.isPayloadAvailable() || doc == null) return null;

		/*
		Collection<byte[]> payloads = s.getPayload();

		int start = -1;
		int end = -1;

		for (Iterator<byte[]> iterator = payloads.iterator(); iterator.hasNext(); ) {
			//I am assuming this iterator corresponds to one span query match
			byte[] bs= iterator.next();
			int currentStart = PayloadHelper.decodeInt(bs, 0);
			int currentEnd = PayloadHelper.decodeInt(bs, 4);
			//logger.log(_level, "start=" + c_start + " end=" + c_end);
			// Expanding to line beginning and line end is done by getting line number later
			// while (c_start > 0 && src.charAt(c_start--) != '\n'); //expand to the front of the first line
			// while (src.charAt(c_end++) != '\n'); //expand to the end of the last line
			if (start == -1 || start > currentStart) start = currentStart;
			if (end == -1 || end < currentEnd ) end = currentEnd;
		}
		*/
		//09Jul2010, ye: The above statements that are commented out are replaced by the next 3 statements
		OffsetAsPayloadDecoder decoder = new OffsetAsPayloadDecoder(s.getPayload());
		int start = decoder.getStart();
		int end = decoder.getEnd();

		if (start < 0 || end < start || end > src.length() ) {
			//sanity check, should not reach here, only if payload returns weired number
			logger.error("This should not happen." +
					" No matching fragments found for this query in document " + doc.get(Indexer.ID) +
					": start=" + start + " end= " + end + "\n");
			return null;
		}


		while (start > 0 && src.charAt(start - 1) != '\n') {
			//expand to the front of the first line, or the first character
			start--;
		}
		while (end < src.length() && src.charAt(end) != '\n') {
			//expand to the end of the line, or end of src
			end++;
		}

		return new int[]{start, end};
	}

	/**
	 * expose for debug purpose
	 * @return
	 */
	public Query getQuery() {
		return _query;
	}

	public NamedList<CloneMatchDoc> getMatchDocs() {
		return _matchDocs;
	}
	/**
	 * for debug, listing all matching document numbers
	 * @return
	 */
	public String listAllMatchDocNums() {
		StringBuilder ss = new StringBuilder();
		Iterator<Map.Entry<String, CloneMatchDoc>> itr = ((Iterable<Entry<String, CloneMatchDoc>>) _matchDocs).iterator();
		while (itr.hasNext()) {
			Map.Entry e = itr.next();
			CloneMatchDoc d = (CloneMatchDoc) e.getValue();
			ss.append("matched: " + e.getKey() + " " + d.getScore() + " " + d.getDocId() + "\n");
		}
		return ss.toString();
	}


}
