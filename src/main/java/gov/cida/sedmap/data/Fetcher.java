package gov.cida.sedmap.data;

import static gov.cida.sedmap.data.DataFileMgr.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.WriterOutputStream;
import org.apache.log4j.Logger;
import org.geotools.filter.AbstractFilter;
import org.opengis.filter.PropertyIsGreaterThan;
import org.opengis.filter.PropertyIsGreaterThanOrEqualTo;
import org.opengis.filter.PropertyIsLessThan;
import org.opengis.filter.PropertyIsLessThanOrEqualTo;

import gov.cida.sedmap.io.FileDownloadHandler;
import gov.cida.sedmap.io.InputStreamWithFile;
import gov.cida.sedmap.io.IoUtils;
import gov.cida.sedmap.io.ReaderWithFile;
import gov.cida.sedmap.io.WriterWithFile;
import gov.cida.sedmap.io.util.StrUtils;
import gov.cida.sedmap.io.util.StringValueIterator;
import gov.cida.sedmap.io.util.exceptions.SedmapException;
import gov.cida.sedmap.ogc.FilterWithViewParams;
import gov.cida.sedmap.ogc.OgcUtils;

public abstract class Fetcher {
	private static final Logger logger = Logger.getLogger(Fetcher.class);

	public static FetcherConfig conf;
	
	private static final List<String> DEFAULT_DAILY_DATA_COLUMN_NAMES = new ArrayList<String>(Arrays.asList(
		"agency_cd",
		"site_no",
		"datetime",
		"DAILY_FLOW",
		"DAILY_FLOW_QUAL",
		"DAILY_SSC",
		"DAILY_SSC_QUAL",
		"DAILY_SSL",
		"DAILY_SSL_QUAL"));
	
	protected String getDataTable(String descriptor) {
		return conf.DATA_TABLES.get(descriptor);
	}
	protected List<Column> getTableMetadata(String tableName) {
		return conf.getTableMetadata(tableName);
	}

	public abstract Fetcher initJndiJdbcStore(String jndiJdbc) throws IOException, Exception;

	protected abstract File handleSiteData(String descriptor, FilterWithViewParams filter, Formatter formatter)
			throws Exception;
	protected abstract File handleDiscreteData(Iterator<String> sites, FilterWithViewParams filter, Formatter formatter)
			throws Exception;

	
	protected String buildBatchSiteURL(String url, Iterator<String> sites, int nwisBatchSize) {
		// site list should be in batches of manageable site IDs
		int batch = 0;
		String sep = "";
		StringBuilder siteList = new StringBuilder();
		while (++batch<nwisBatchSize && sites.hasNext()) {
			siteList.append(sep).append(sites.next());
			sep=",";
		}
		String sitesUrl = url.replace("_sites_",   siteList);
		
		logger.debug("NWIS site list: " + siteList);
		return sitesUrl;
	}
	
	/**
	 * Change for JIRA NSM-249
	 * 
	 * 		We need to combine all sites into one giant data block and also
	 * 		include all possible headers.  The headers defined are:
	 * 
	 * 			agency_cd, site_no, datetime, DAILY_FLOW, DAILY_FLOW_QUAL, DAILY_SSC, DAILY_SSC_QUAL, DAILY_SSL, DAILY_SSL_QUAL
	 * 
	 * 		If a site does not contain a value for one of these columns then
	 * 		the value is left empty (no value... nothing).
	 * 
	 *  	We will add a comment to the comment block that states:
	 *  
	 *  		"Missing data (empty values for a column) indicates that these data were not present within the USGS National Water Information System"
	 *  
	 *  	Logic:
	 *  
	 *  		Since we need to print out all columns for every site, we must
	 *  		read each site's columns, put it into the format of the columns
	 *  		requested (using empty values for columns missing) and then
	 *  		input the values as we see them.
	 *  
	 *  		The list DEFAULT_DAILY_DATA_COLUMN_NAMES contains all of the
	 *  		necessary headers required for the daily_data results in the
	 *  		order they need to be placed.
	 *  
	 *  		The following map currentHeaderValueMapping will be used to
	 *  		record each line's value to the appropriate header for each
	 *  		value found.
	 *  
	 *  		At the beginning of each site's data block, we will record
	 *  		the site's resulting header in a list in the order in which it
	 *  		was returned in the request.
	 *  
	 *  		Then, as we iterate through each result row of the site's data,
	 *  		we will associate the correct value to the correct header for
	 *  		that specific header list and then add the value to the overall
	 *  		currentHeaderValueMapping so we can correctly place the line's
	 *  		value in the correct overall spot in the data block.
	 *  
	 *  		Subsequently, each time we see a new Site we will null out all
	 *  		values in the map because we don't know which columns are 
	 *  		for that site until we parse it.
	 */	
	protected static class ColumnManager {
		Map<String, String> values = new HashMap<>();
		List<String> columnNames   = new ArrayList<String>();
		boolean columnsWritten = false;
		RdbFormatter rdb = new RdbFormatter();
		
		final Formatter formatter;
		final String columnHeader;
		
		public ColumnManager(Formatter formatter) {
			this.formatter = formatter;
			this.columnHeader = join(DEFAULT_DAILY_DATA_COLUMN_NAMES);
		}
		
		protected String join(List<String> columns) {
			StringBuilder join = new StringBuilder();
			String colHeaderSep = "";
			for (String column : columns) {
				join.append(colHeaderSep).append(column);
				colHeaderSep = formatter.getSeparator();
			}
			return join.toString();
		}
		
		// We have now parsed the entire result row and associated each
		// value with its correct header key mapping.  Now we have to
		// loop through the original DEFAULT_DAILY_DATA_COLUMN_NAMES
		// list and create an entry for each value that is in the map
		// for each header.  If the value is null, we just keep it empty.
		protected String joinValues() {
			StringBuilder join = new StringBuilder();
			String colHeaderSep = "";
			for (String column : DEFAULT_DAILY_DATA_COLUMN_NAMES) {
				String value = getValue(column);
				if (value != null) {
					join.append(colHeaderSep).append(value);
				}
				colHeaderSep = formatter.getSeparator();
			}
			return join.toString();
		}

		
		/**
		 * This is the first site found in the resulting
		 * data.  It is also the first time we have seen
		 * column headers which means we need to write them
		 * all out in order.
		 */
		protected void writeOnce(WriterWithFile writer) throws IOException  {
			if ( ! columnsWritten) {
				columnsWritten = true;
				writer.writeLine(columnHeader);
			} 
		}

		//NB: order of replacements is important. Change with caution.
		protected String reconditionLine(String line) {
			line = line.replaceAll("\\d+_00060_00003_cd", "DAILY_FLOW_QUAL");
			line = line.replaceAll("\\d+_00060_00003",    "DAILY_FLOW");
			line = line.replaceAll("\\d+_80155_00003_cd", "DAILY_SSL_QUAL");
			line = line.replaceAll("\\d+_80155_00003",    "DAILY_SSL");
			line = line.replaceAll("\\d+_80154_00003_cd", "DAILY_SSC_QUAL");
			line = line.replaceAll("\\d+_80154_00003",    "DAILY_SSC");
			return line;
		}
		
		public void makeColmnNamesHumanReadable(String line) {
			// make column names human-readable
			String currentHeaderline = reconditionLine(line);
			
			// Now lets save this site's columns so we can correctly place
			// the values in the correct spots when writing out the line. 
			// Since the NWIS data always comes in rdb format, we use that
			// formatter's line separator to determine how we split the lines.
			columnNames = new ArrayList<String>(Arrays.asList(currentHeaderline.split(rdb.getSeparator(), -1)));
			
			// Now what we need to do is null out all of the values for the 
			// current headerMapping that are NOT a part of this site's results
			for (String columnKey : DEFAULT_DAILY_DATA_COLUMN_NAMES) {
				values.put(columnKey, null);
			}
			
		}

		public int columnSize() {
			return columnNames.size();
		}
		
		protected boolean isColumnCountMatch(String ... values) {
			return values.length == columnSize();
		}

		public String getName(int index) {
			return columnNames.get(index);
		}

		public boolean containsValueKey(String headerKey) {
			return values.containsKey(headerKey);
		}

		public void putValue(String column, String value) {
			values.put(column, value);			
		}

		public String getValue(String column) {
			return values.get(column);
		}

		public String formateLine(String line, boolean logLineError) {
			// translate from NWIS RDB format to requested format
			String formattedRawline = formatter.transform(line, rdb);
			
			// We now have the values in the order in which they belong
			// to their original currentHeaderNames list.  We need to
			// split them and then add them to the currentHeaderValueMapping
			// accordingly.
			String[] values =formattedRawline.split(formatter.getSeparator(), -1);
			
			if ( ! isColumnCountMatch(values) ) {
				// this prevents a major log spam
				if (logLineError) {
					// this is too noisy and requires a new story to address it,
					logger.error("Daily Data Parsing ERROR: The number of values in the line [" + Arrays.toString(values) + "] SIZE (" + values.length + 
							") does not equate to the number of column headers we have for this line [" + columnNames + "] SIZE (" + columnNames.size() +
							")\nLINE BEFORE TRANSFORM:\n[" + line + "]\n" +
							"\nLINE AFTER  TRANSFORM:\n[" + formattedRawline + "]\n" +
							").  Skipping line...");
				}
				return null;
			}
			
			// Now loop through our values, associate them with the line's header
			// column name in currentHeaderNames and then put the value in the
			// currentHeaderValueMapping value for that header key.
			for (int i = 0; i < values.length; i++) {
				String value = values[i];
				
				// Check to see if the index is out of bounds of the current header list
				if (columnSize() < i) {
					logger.error("Daily Data Parsing ERROR: The headers for this line [" + columnNames + "] do not contain a value at value index [" + i + "].  Skipping line...");
					return null;
				}
				
				// Get the header name for this value index
				String headerKey = getName(i);
				
				// Make sure this header name exists as a key in the map 
				if ( ! containsValueKey(headerKey) ) {
					logger.error("Daily Data Parsing ERROR: The header for this  [" + headerKey + "] at value index [" + i + "] is not present in the current header mapping.  Skipping line...");
					return null;
				}
				
				// Now put the value for this header into the map at this key position
				putValue(headerKey, value);
			}
			
			String finalResult = joinValues();
			return finalResult;
		}
	}
	
	protected File handleNwisData(Iterator<String> sites, FilterWithViewParams filter, Formatter formatter, FileDownloadHandler handler)
			throws Exception {
		
		int nwisBatchSize = conf.getBatchSize();
		int nwisRetryMax  = conf.getRetries();
		
		if ( ! sites.hasNext() ) {
			// return nothing if there are no sites
			return null;
		}

		// NWIS offers RDB only that is compatible with sedmap needs
		String format = "rdb";
		String url = conf.getNwisUrl().replace("_format_", format); // this is non-destructive

		// extract expressions from the filter we can handle
		String yr1 = filter.getViewParam("yr1");
		String yr2 = filter.getViewParam("yr2");

		// translate the filters to NWIS Web query params
		String startDate = yr1 + "-01-01"; // Jan  1st of the given year
		String endDate   = yr2 + "-12-31"; // Dec 31st of the given year

		url = url.replace("_startDate_", startDate);
		url = url.replace("_endDate_",   endDate);
		logger.debug("NWIS PRE-SITE URL: " + url);
		// we need to include the second header line for rdb format

		int readLineCountAfterComments = 0;
		String sitesUrl = null;
		WriterWithFile nwisTmp = null;
		
		try {
			// open temp file
			nwisTmp = IoUtils.createTmpZipWriter(DAILY_FILENAME, formatter.getFileType());
			nwisTmp.write(formatter.fileHeader(HeaderType.DAILY));
			
			while ( sites.hasNext() && handler.isAlive() ) {
				
				sitesUrl = buildBatchSiteURL(url, sites, nwisBatchSize);

				int batchAttempts = 0; // keep track of batch attempts
				WriterWithFile batchTmp = null;
				ReaderWithFile nwis = null;
				while (batchAttempts++ <= nwisRetryMax) { // try again a limited number of times
					logger.debug("NWIS WEB attempt starting number " +batchAttempts);

					try {
						// NSM-297 Retrying when NWIS WEB connections are lost
						batchTmp = IoUtils.createTmpZipWriter(BATCH_FILENAME, formatter.getFileType());
						logger.debug("NWIS WEB URL: " + sitesUrl);
						nwis = fetchNwisData(sitesUrl);
						logger.debug("formatting NWIS data");
	
						ColumnManager currentColumns = new ColumnManager(formatter);
						
						int lineErrors =  0; // only log a few error lines
						
						String line;
						while ( (line = nwis.readLine()) != null ) { // fetch next line
							// ignore all comment lines
							if ( line.startsWith("#") ) {
								readLineCountAfterComments = 0;
								continue;
							}
							// include column headers for the next site
							if (0 == readLineCountAfterComments) {
								readLineCountAfterComments++;
								
								currentColumns.writeOnce(nwisTmp);
								currentColumns.makeColmnNamesHumanReadable(line);
								
								continue;
							}
							//exclude NWIS row following column headers for each site
							if (1 == readLineCountAfterComments) {
								readLineCountAfterComments++;
								continue;
							}
	
							String formattedLine = currentColumns.formateLine(line, lineErrors < nwisRetryMax);
							
							if (formattedLine == null) {
								lineErrors++;
							} else {
								batchTmp.writeLine(formattedLine);
							}
						}
					} catch (IOException ioe) {
						if (batchAttempts < nwisRetryMax) {
							logger.warn("NWIS WEB attempt number " +batchAttempts+ " failed, trying again.", ioe);
							// clean up and try again
							IoUtils.quietClose(nwis, batchTmp);
							batchTmp.deleteFile();
							if (nwis != null) {
								nwis.deleteFile();
							}
							continue;
						} else {
							logger.error("Possible EOF Issue.", ioe);
							throw ioe; // too many tries
						}
					} finally {
						IoUtils.quietClose(nwis, batchTmp);
						if (nwis != null) {
							nwis.deleteFile();
						}
					}
					try {
						IoUtils.copy(batchTmp.getFile(), nwisTmp);
						batchAttempts += nwisRetryMax; // successful try so we are done
					} finally {
						batchTmp.deleteFile();
					}
				}
			}
		} catch (Exception e) {
			if (null != nwisTmp) {
				// first, construct an error message
				final String newline = "\r\n";  //we always want windows newlines in error messages
				StringBuilder errMsgBuilder = new StringBuilder();
				errMsgBuilder.append("Error Retrieving Daily Data");
				errMsgBuilder.append(newline);
				if (null != sitesUrl && sitesUrl.length() > 0 ) {
					errMsgBuilder.append("There was an error acquiring or processing the data from the url:");
					errMsgBuilder.append(newline);
					errMsgBuilder.append(sitesUrl);
				} else {
					errMsgBuilder.append("There was an error forming the url for the NWIS web query");
				}
				
				// second, delete the data file that has an error
				nwisTmp.deleteFile();
				// then, create a new file for with an error message
				try ( WriterWithFile msgFile = IoUtils.createTmpZipWriter(DAILY_FILENAME, formatter.getFileType()) ) {
					// now, write the message in the new data file
					msgFile.write( errMsgBuilder.toString() );
				}
				
				// finally, append more for the log
				logger.error(errMsgBuilder.toString(), e);
			}
			throw e;
		} finally {
			IoUtils.quietClose(nwisTmp);
		}

		return nwisTmp.getFile();
	}



	protected ReaderWithFile fetchNwisData(String urlStr) throws Exception {
		logger.debug("fetching NWIS data");
		URL url = new URL(urlStr);
		URLConnection cn = url.openConnection();
		
		// wait for connection and data
		final int timeout = 60000;// 60sec
		cn.setConnectTimeout(timeout);
		cn.setReadTimeout(timeout);

		// build a reader waiting for stream ready
		ReaderWithFile reader = null;
		int nwisTriesCount = 0;
		while (null == reader && nwisTriesCount++ < conf.getRetries() ) {
			try (InputStream input = cn.getInputStream()) {
				
				WriterWithFile writer = IoUtils.createTmpZipWriter(NWIS_FILENAME, new RdbFormatter().getFileType());
				WriterOutputStream out = new WriterOutputStream(writer);
				IOUtils.copy(input, out);
				IoUtils.quietClose(input, out);
				
				InputStream fileIn = IoUtils.createTmpZipStream(writer.getFile());
				reader = new ReaderWithFile(fileIn, writer.getFile());
				
			} catch (IOException e) {
				// if we cannot access the stream then wait a second
				if (nwisTriesCount > conf.getRetries() ) {
					throw e;
				}
				Thread.sleep(1000);
			}
			nwisTriesCount++;
		}

		return reader;
	}


	public void doFetch(HttpServletRequest req, FileDownloadHandler handler) throws Exception {
		logger.debug("doFetch");

		String    dataTypes = getDataTypes(req);			// Search Filter(s)
		Formatter formatter = getFormatter(req);			// Download Choice
		boolean   alsoFlow  = getDiscreteFlow(req);			// Download Choice
		List<String> sites  = new LinkedList<String>();

		/**
		 * We will start writing the files here.
		 * 
		 * 	Important:  If this handler is an email handler we actually close
		 * 				the client's response output stream so that the browser
		 * 				can continue and not be blocked by this execution thread.
		 */
		handler.beginWritingFiles();

		/**
		 * Future improvement.
		 * 
		 * 		Its possible to have multiple datasources to contact in order to get
		 * 		what we need.  The following for-loop is serial meaning it
		 * 		does one at a time.  We can improve this by threading the 
		 * 		datasource calls so they all go off at the same time.
		 * 
		 * 		Unfortunately, some of the calls rely on a previous datasource's
		 * 		response.  So at most we can cut the logic in half.
		 */
		for (String site  : conf.DATA_TYPES) { // check for daily and discrete
			if ( ! dataTypes.contains(site)  ||  ! handler.isAlive()) {
				continue;
			}

			String    ogcXml = getFilter(req, site);
			AbstractFilter aFilter = OgcUtils.ogcXmlToFilter(ogcXml);
			String yr1 = OgcUtils.removeFilter(aFilter, "year", PropertyIsGreaterThanOrEqualTo.class, PropertyIsGreaterThan.class);
			String yr2 = OgcUtils.removeFilter(aFilter, "year", PropertyIsLessThanOrEqualTo.class,    PropertyIsLessThan.class);
			FilterWithViewParams filter = new FilterWithViewParams(aFilter);
			filter.putViewParam("yr1", "1850", yr1);
			filter.putViewParam("yr2", "2100", yr2);

			for (String value : conf.DATA_VALUES) { // check for sites and data
				long startTime = System.currentTimeMillis();
				if ( ! dataTypes.contains(site)  ||  ! handler.isAlive()) {
					continue;
				}

				StringBuilder   name = new StringBuilder();
				String    descriptor = name.append(site).append('_').append(value).toString();
				String      filename = descriptor + formatter.getFileType();

				File fileData = null;
								
				try {
					// TODO this was originally going to be a single call but reality got in the way - could use a refactor
					if ( DAILY_FILENAME.equals(descriptor) ) {
						fileData = handleNwisData(sites.iterator(), filter, formatter, handler);
						
					} else if ( DISCRETE_FILENAME.equals(descriptor) ) {
						fileData = handleDiscreteData(sites.iterator(), filter, formatter);
						
					} else if (descriptor.contains("sites") ) {
						fileData = handleSiteData(descriptor, filter, formatter);
						
						List<String> descriptorSites = makeSiteIterator(fileData, formatter);

						if ( ! alsoFlow) { // if the user does not want discrete flow data
							sites.clear(); // do not retain the discrete sites for NWIS daily data
						}

						if (sites.size() == 0) { // this is a short circuit to prevent an unnecessary loop
							sites = descriptorSites;
						} else {
							// TODO refactor to use sorted set
							for (String siteNo : descriptorSites) {
								if ( ! sites.contains(siteNo) ) {
									sites.add(siteNo);
								}
							}
						}
					}
					
					// if we found some sites or data then append it to the collection
					if ( fileData != null && !sites.isEmpty() ) {
						InputStreamWithFile stream = IoUtils.createTmpZipStream(fileData);
						handler.writeFile(formatter.getContentType(), filename, stream);
					}
				} catch (Exception e) {
					if (e instanceof SedmapException) {
						throw e;
					} else {
						logger.error("Failed to fetch from the Database.  Exception is:" +  e.getMessage());
						logger.error("Due to internal exception caught, throwing generic OGC error for error handling on the client side.");
						throw new SedmapException("Error while transfering data", e);
					}
				} finally {
					IoUtils.deleteFile(fileData);
				}

				long totalTime = System.currentTimeMillis() - startTime;
				logger.info(toString() + ": " +descriptor+ " request time (ms) " + totalTime);
			}
		}
		handler.finishWritingFiles(); // done writing files

	}


	// TODO this needs a bit of fine tuning because we want to be able to read the site data twice.
	// once for the use return download and again for the NWIS site list.
	// right now, it assumes that the data comes from a cached file.
	// it uses the formatter that created the file to know how to split the file
	protected List<String> makeSiteIterator(File file, Formatter formatter) throws IOException {

		LinkedList<String> sites = new LinkedList<String>();

		if (file==null) {
			return StringValueIterator.EMPTY;
		}

		try ( InputStream       fin = IoUtils.createTmpZipStream(file);
			  InputStreamReader rin = new InputStreamReader(fin);
			  BufferedReader reader = new BufferedReader(rin);
			) {

			String line;
			while ( (line=reader.readLine()) != null ) {
				int pos = line.indexOf( formatter.getSeparator() );
				if (pos == -1) continue; // trap empty lines
				String site = line.substring(0, pos );
				// only preserve site numbers and not comments or header info
				if (site.matches("^\\d+$")) {
					sites.add(site);
				}
			}
		}

		return sites;
	}



	protected String getFilter(HttpServletRequest req, String dataType) {
		String ogcXml = req.getParameter(dataType+"Filter");

		if (ogcXml == null) {
			logger.warn("Failed to locate OGC 'filter' parameter - using default");
			// TODO empty result
			return "";
		}

		return ogcXml;
	}



	protected boolean getDiscreteFlow(HttpServletRequest req) {
		String  flow     = req.getParameter("dataTypes");
		boolean alsoFlow = ! StrUtils.isEmpty(flow) && flow.toLowerCase().contains("flow");
		return alsoFlow;
	}


	protected String getDataTypes(HttpServletRequest req) {
		String types = req.getParameter("dataTypes");

		// expecting a string with terms "daily_discrete_sites_data" in it
		// sites means site data - no samples
		// data  means site data - no site info
		// daily and discrete refer to samples
		// - "daily_discrete_sites_data" means they want all data
		// - "daily_sites" means they want all daily site info only

		if (types == null) {
			logger.warn("Failed to locate 'dataTypes' parameter - using default");
			types = "daily_discrete_sites";
		}

		return types;
	}



	protected Formatter getFormatter(HttpServletRequest req) {
		String format = req.getParameter("format");
		format = conf.FILE_FORMATS.containsKey(format) ?format :"tsv";

		Formatter formatter = null;
		try {
			formatter = conf.FILE_FORMATS.get(format).newInstance();
		} catch (Exception e) {
			logger.warn("Could not instantiate formatter for '" +format+"' with class "
					+conf.FILE_FORMATS.get(format)+". Using RDB as fall-back.");
			formatter = new RdbFormatter();
		}

		return formatter;
	}

}
