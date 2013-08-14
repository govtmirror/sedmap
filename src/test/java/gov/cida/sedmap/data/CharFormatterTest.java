package gov.cida.sedmap.data;

import static org.junit.Assert.*;

import java.sql.Date;
import java.sql.Types;
import java.util.List;

import gov.cida.sedmap.data.Column;
import gov.cida.sedmap.io.IoUtils;
import gov.cida.sedmap.mock.MockDataSource;
import gov.cida.sedmap.mock.MockResultSet;
import gov.cida.sedmap.mock.MockRowMetaData;

import org.junit.Before;
import org.junit.Test;

public class CharFormatterTest {

	MockDataSource     ds;
	MockResultSet      rs;
	MockRowMetaData    md;
	String sql;


	@Before
	@SuppressWarnings("deprecation")
	public void setup() throws Exception {
		// init values
		ds  = new MockDataSource();
		rs  = new MockResultSet();
		md  = new MockRowMetaData();

		md.addMetadata( new Column("Site_Id",     Types.VARCHAR, 10, false) );
		md.addMetadata( new Column("Latitude",    Types.NUMERIC,  3, false) );
		md.addMetadata( new Column("Longitude",   Types.NUMERIC,  3, false) );
		md.addMetadata( new Column("create_date", Types.DATE,     8, false) ); // TODO 8 is a place-holder
		rs.addMockRow("1234567891",40.1,-90.1,new Date(01,1-1,1));
		rs.addMockRow("12345678|2",40.2,-90.2,new Date(02,2-1,2));
		rs.addMockRow("1234567893",40.3,-90.3,new Date(03,3-1,3));

		sql = "select * from dual";
		// populate result sets
		ds.put(sql, rs);
		ds.put(sql, md);
	}


	@Test
	public void getTableColumns() throws Exception {
		List<Column> cols = new CharSepFormatter("","","").getTableColumns(rs);

		assertEquals(4, cols.size());
		assertEquals("Site_Id", cols.get(0).name);
		assertEquals("Longitude", cols.get(2).name);
		assertEquals(Types.VARCHAR, cols.get(0).type);
		assertEquals(Types.DATE, cols.get(3).type);
		assertEquals(3, cols.get(1).size);
		assertEquals(8, cols.get(3).size);
	}


	@Test
	public void getFileHeader() throws Exception {
		String actual = new CharSepFormatter("","|","").fileHeader(rs);
		String expect = "Site_Id|Latitude|Longitude|create_date"+IoUtils.LINE_SEPARATOR;
		assertEquals(expect,actual);
	}


	@Test
	public void getFileRows() throws Exception {
		rs.next();
		String actual = new CharSepFormatter("","|","").fileRow(rs);
		String expect = "1234567891|40.1|-90.1|1901-01-01"+IoUtils.LINE_SEPARATOR;
		assertEquals(expect,actual);
	}


	@Test
	public void getFileRows_ensureQuoteAroundDataContainingDelimitor() throws Exception {
		rs.next(); // by pass first row
		rs.next();
		String actual = new CharSepFormatter("","|","").fileRow(rs);
		String expect = "\"12345678|2\"|40.2|-90.2|1902-02-02"+IoUtils.LINE_SEPARATOR;
		assertEquals(expect,actual);
	}


	@Test
	public void constructor() throws Exception {
		String contentType   = "a";
		String separator     = "b";
		String fileType      = "c";
		CharSepFormatter frm = new CharSepFormatter(contentType, separator, fileType);

		assertEquals(contentType, frm.getContentType());
		assertEquals(separator, frm.getSeparator());
		assertEquals(fileType, frm.getFileType());

	}


}
