package edu.uci.mhlee.jihyunp.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.ServletRequestUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;


import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.TreeMap;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.util.List;
import java.util.Map;
import java.util.Scanner;

import edu.uci.ics.crawler4j.parser.HtmlParseData;
import edu.uci.mhlee.*;
 
@Controller
public class WebSearchHelloWorld {
	
	static PropertyReader pr = null;
	static {
		try {
			pr = new PropertyReader();
			Class.forName("org.sqlite.JDBC");
		} catch(Exception e) { e.printStackTrace(); }
	}

//	@RequestMapping(value="/welcome", method=RequestMethod.GET)
//	public static String foo(@RequestParam("SearchBox") String searchQuery)
//	{
//		return searchQuery;
//	}


	@RequestMapping("/welcome")
	public static ModelAndView printDBRecord(HttpServletRequest request, HttpServletResponse response)
	{

		long startTime, endTime, ndcgStartTime, ndcgEndTime;
		double totalTime;
		startTime = System.currentTimeMillis();
		Map<Integer, Double> docScoreMap = new HashMap<Integer, Double>();
		Map<Integer, String> posMap = null;//new HashMap<Integer, String>();
		List<Map<Integer, String>> posMapList = new ArrayList<Map<Integer, String>>();
		
		String query = ServletRequestUtils.getStringParameter(request, "SearchBox", "");
		String[] queryParts = query.split(" ");
		int queryLength = queryParts.length;
		int rankType = 2;	
		
//		double anchorWeight = 30;
//		double titleWeight = 30;
//		double pageRankWeight = 1;
//		double pageRankMax = 9999;
//		double pageRankInit = 0.5;
		
		double anchorWeight = 100;
		double titleWeight = 100;
		double pageRankWeight = 1;
		double pageRankMax = 9999;
		double pageRankInit = 0.5;
		
		
		String strmsg = "";
		
		Connection connection = null;
		try
		{
			connection = DriverManager.getConnection("jdbc:sqlite:"+pr.getDbPath());
			Statement statement = connection.createStatement();
			statement.setQueryTimeout(30);  // set timeout to 30 sec.

			Map<Integer, Double> pRank = PageRank.readPageRank();
			
			//String word;
			int tf, df, docid;
			double tfidf;
			String pos;
			double NDCG5;

			int queryCnt = 0;
			for(String queryWord : queryParts){
				Map<Integer, String> curPosMap = new HashMap<Integer, String>();
				
				String extra = "";
				if (queryWord.matches(".*s$"))
					extra = " or word = '" + queryWord.replaceAll("s$", "") + "'";
					
				// First load DFs into dfMap

				{
					//Based on text content
					String curQuery = "select docid, tf, df, tfidf, pos from invertedIndex where word = '"+queryWord+"'"+ " or word = '" + capitalize(queryWord) + "'" + extra;
					//System.out.println(curQuery);
					ResultSet rs = statement.executeQuery(curQuery);

					while (rs.next())
					{
						// read the result set
						//word = rs.getString("word");
						docid = rs.getInt("docid");
						tf = rs.getInt("tf");
						df = rs.getInt("df");
						tfidf = rs.getDouble("tfidf");
						pos = rs.getString("pos");


						if(docScoreMap.get(docid) == null) docScoreMap.put(docid, 0.0);
						//Individual adding
						if(rankType == 1){
							docScoreMap.put(docid, docScoreMap.get(docid)+tfidf);	
						}else if(rankType == 2){
							//Just copy only for the first query word
							if(queryCnt==0){
								curPosMap.put(docid, pos);
								docScoreMap.put(docid, docScoreMap.get(docid)+tfidf);	
							}
							//Need to check from second query word
							else{
								//Co-occurrence
								if(posMap.get(docid) != null){
									String matchedPos = Utils.neighborPosString(posMap.get(docid), pos, 1);
									curPosMap.put(docid, matchedPos);
									int matchedPosCnt = 0;
									if(matchedPos.length() > 0)
										matchedPosCnt = matchedPos.split(",").length;

					
									docScoreMap.put(docid, docScoreMap.get(docid)+tfidf*(Math.log(1+matchedPosCnt)+1));

									//log(1+matchedPosCnt)+1
								}
							}
						}
					}
					posMap = curPosMap;
					posMapList.add(posMap);
					
				}
				
				{
					//Add Anchor weight
					String curQuery = "select docid, df from invertedIndexAnchor where type = 'anchor' and word = '"+queryWord+"'"+ " or word = '" + capitalize(queryWord) + "'" + extra;
					//System.out.println(curQuery);
					ResultSet rs = statement.executeQuery(curQuery);
					while (rs.next())
					{
						// read the result set
						//word = rs.getString("word");
						docid = rs.getInt("docid");
						df = rs.getInt("df");
						
//						System.out.println("AnchorDocid:" + docid);
						if(docScoreMap.get(docid) == null) docScoreMap.put(docid, 0.0);
						double curScore = docScoreMap.get(docid);
//						docScoreMap.put(docid, curScore+anchorWeight/df);
						docScoreMap.put(docid, curScore+anchorWeight);
					}
				}
				
				{
					//Add Title weight
					String curQuery = "select docid, df from invertedIndexAnchor where type = 'title' and word = '"+queryWord+"'"+ " or word = '" + capitalize(queryWord) + "'" + extra;
					//System.out.println(curQuery);
					ResultSet rs = statement.executeQuery(curQuery);
					while (rs.next())
					{
						// read the result set
						//word = rs.getString("word");
						docid = rs.getInt("docid");
						df = rs.getInt("df");
						
//						System.out.println("Title Docid:" + docid);
						if(docScoreMap.get(docid) == null) docScoreMap.put(docid, 0.0);
						double curScore = docScoreMap.get(docid);
//						docScoreMap.put(docid, curScore+titleWeight/df);
						docScoreMap.put(docid, curScore+titleWeight);
					}
				}
				
				queryCnt++;
			}
			
			//add pageRank Concept
			for(Integer curDocid : docScoreMap.keySet()){
				Double curScore = docScoreMap.get(curDocid);
				Double pVal = pRank.get(curDocid);
				if(pVal == null) pVal = pageRankInit;
				
				docScoreMap.put(curDocid, curScore + pageRankWeight*Math.min(pVal, pageRankMax));
			}
			
			
			docScoreMap = Utils.sortByValueDouble(docScoreMap);
			
		
			ndcgStartTime = System.currentTimeMillis();
			// Calculate NDCG Score
			{
				// Get GoogleList
				ArrayList<String> googleList = SearchEngine.getTopURLsFromGoogle(query);
				
				// Get GobuciList
				ArrayList<String> gobuciList = new ArrayList<String>();
				for(Integer key : docScoreMap.keySet())
				{
					String curQuery = "select url from webContents where docid = "+key;
					ResultSet rs = statement.executeQuery(curQuery);
					String url;
					int cnt = 0;
					while (rs.next())
					{
						url = rs.getString("url");
						gobuciList.add(url);
						if (cnt == 5) break;
					}
				}
				
				NDCG5 = SearchEngine.computeNDCG5(googleList, gobuciList);
				// Print NDCG@5 Score
				System.out.println("\nNDCG@5: "+NDCG5);
			}
			ndcgEndTime = System.currentTimeMillis();
			
			String url, subdomain, path, snip, html, title;
			String[] textArray;
			int firstPos;
			
			int j = 0;
			for (Integer key : docScoreMap.keySet())
			{
				//System.out.println(key+"/"+wordFrequencies.get(key));
				
				String curQuery = "select docid, url, title, path, text, html from webContents where docid = "+key;
				//System.out.println(curQuery);
				ResultSet rs = statement.executeQuery(curQuery);
				String position = "";

				while (rs.next())
				{
					// read the result set
					docid = key;
					url = rs.getString("url");
					path = rs.getString("path");
					title = rs.getString("title");
					textArray = rs.getString("text").split("[^a-zA-Z']+");
	
					position = "";
					
//					firstPos = Integer.parseInt(posMap.get(key).split(",")[0]);
					for (int l=queryLength-1; l>=0; l--)
					{
						position = posMapList.get(l).get(docid);
						if (position == null)
							position = "";
						else
							break;
					}
					
					if (position == "")
					{
						snip = "no pos found";
						break;
					}
					else
					{
						int intPos = Integer.parseInt(position.split(",")[0]);
						int fromIdx = Math.min( Math.max(0,  intPos-30), textArray.length-1);
						int toIdx = Math.min(intPos+30, textArray.length-1);
						String[] subTextArray = Arrays.copyOfRange(textArray, fromIdx, toIdx);
						
						snip = String.join(" ", subTextArray);
						for (String queryWord : queryParts)
						{
							snip = snip.replaceAll("(?i)\\b"+queryWord+"\\b", "<b>"+queryWord+"</b>");
							snip = snip.replaceAll("(?i)\\b"+capitalize(queryWord)+"\\b", "<b>"+capitalize(queryWord)+"</b>");
						}
					}
					

					// Title
					strmsg += "<big><a style=\"font-weight: bold;\" class=\"one\" href=\"" + url + "\"> <img src=\"gobuciface2.png\" height=\"13\" width=\"20\"> "+ title + "</a></big><br>";
					// URL and snippet
					strmsg += "<a class=\"two\" href=\"" + url + "\">"+url+"</a>"+ 
							  "<br>"+ snip +" ...<br><br><br>" ;

//					strmsg += "<h4><a href=\"" + url +"\"> Rank " + (j+1) + "</a></h4>  <b>URL</b>: <a href=\"" + url + "\">"+url+"</a>"+ 
//							  "<br><br>" ;
//					strmsg += "<h4><a href=\"" + url +"\"> Rank " + (j+1) + "</a></h4>  <b>Score</b>:" + docScoreMap.get(key) +
//							   ", <b>DocID</b>: " + docid + "<br> <b>URL</b>: " + url + "<br> <b>SubDomain</b>: " + subdomain +
//							   "<br> <b>Path</b>:"+path + "<br> "+ snip +"<br><br>" ;
//					
					j++;
					if (j > 14) {j = 100; break;}
//					else if (position == "") break;
				}
				
				if (j==100) break;
				
			}
			
			endTime = System.currentTimeMillis();
			totalTime = (endTime - startTime - ndcgEndTime + ndcgStartTime) / 1000.0;
			if (j == 100)
				strmsg = "<small style=\"color: rgb(127, 127, 127);\">More than 15 results (" 
				           + totalTime + " seconds)<br>NDCG@5: "+ String.format("%.4f", NDCG5) +"</small><br><br><br>"+ strmsg;
			else
				strmsg = "<small style=\"color: rgb(127, 127, 127);\">" + j + " results (" 
		  	              + totalTime + " seconds)<br>NDCG@5: "+ String.format("%.4f", NDCG5) +"</small><br><br><br>"+ strmsg;
			
			
		}
		catch(SQLException e)
		{
			// if the error message is "out of memory", 
			// it probably means no database file is found
			System.err.println(e.getMessage());
		}
		finally
		{
			try
			{
				if(connection != null)
					connection.close();
			}
			catch(SQLException e)
			{
				// connection close failed.
				System.err.println(e);
			}
		}
		
		return new ModelAndView("welcome", "message", strmsg);
	}
	
	
	private static String capitalize(final String line) {
		   return Character.toUpperCase(line.charAt(0)) + line.substring(1);
		}
	
//	
//	public static ModelAndView printDBRecord(Map<Integer, Double> wordFrequencies)
//	{
//		Connection connection = null;
//		String strmsg = "";
//		try
//		{
//			// create a database connection
//			connection = DriverManager.getConnection("jdbc:sqlite:"+pr.getDbPath());
//			Statement statement = connection.createStatement();
//			statement.setQueryTimeout(30);  // set timeout to 30 sec.
//
//			//while(query.length() >= 0){
//			//query = keyboard.nextLine();
//
//			int docid;
//			String url;
//			String subdomain;
//			String path;
//			
//			int j = 0;
//			for(Integer key : wordFrequencies.keySet()){
//				//System.out.println(key+"/"+wordFrequencies.get(key));
//				
//				String curQuery = "select docid, url, subdomain, path from webContents where docid = "+key;
//				//System.out.println(curQuery);
//				ResultSet rs = statement.executeQuery(curQuery);
//
//				while (rs.next())
//				{
//					// read the result set
//					docid = rs.getInt("docid");
//					url = rs.getString("url");
//					subdomain = rs.getString("subdomain");
//					path = rs.getString("path");
//					
//					strmsg += strmsg + "Rank <"+(j+1)+"> Score:"+wordFrequencies.get(key)+", DocID: "+docid+" / URL: "+url+" / SubDomain: "+subdomain+" / Path:"+path + "<br>";
////					System.out.println("Rank <"+(j+1)+"> Score:"+wordFrequencies.get(key)+", DocID: "+docid+" / URL: "+url+" / SubDomain: "+subdomain+" / Path:"+path);
////					System.out.println("");
//				}
//				
//				j++;
//				if(j > 10) break;
//			}
//			
//		}
//		catch(SQLException e)
//		{
//			// if the error message is "out of memory", 
//			// it probably means no database file is found
//			System.err.println(e.getMessage());
//		}
//		finally
//		{
//			try
//			{
//				if(connection != null)
//					connection.close();
//			}
//			catch(SQLException e)
//			{
//				// connection close failed.
//				System.err.println(e);
//			}
//		}
//
//		
//		String htmlmsg = "<br><div style='text-align:center;'>" + "<h3> " + strmsg + " </div><br><br>";
//		return new ModelAndView("welcome", "message", htmlmsg);
//	}
//	
	
//	
//	@RequestMapping("/welcome")
//	public ModelAndView helloWorld() {
//		String message = "<br><div style='text-align:center;'>"
//				+ "<h3>********** Hello World </h3>This message is coming from WebSearchHelloWorld.java **********</div><br><br>";
//		return new ModelAndView("welcome", "message", message);
//	}
}
