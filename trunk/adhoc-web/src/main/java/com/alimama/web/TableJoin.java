package com.alimama.web;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.Job;
import org.apache.log4j.Logger;

import backtype.storm.utils.Utils;

import com.alimama.mdrill.adhoc.ExecutorSerives;
import com.alimama.mdrill.adhoc.MySqlConn;
import com.alimama.mdrill.adhoc.MysqlInfo;
import com.alimama.mdrill.index.MakeIndex;
import com.alimama.mdrill.index.MakeIndex.updateStatus;
import com.alimama.mdrill.json.JSONArray;
import com.alimama.mdrill.json.JSONException;
import com.alimama.mdrill.json.JSONObject;
import com.alimama.mdrill.ui.service.MdrillService;
import com.alimama.mdrill.utils.HadoopUtil;
import com.alimama.web.adhoc.Upload;
/**
 * createTable.jsp
 * tableJoinList.jsp
 * upload.jsp
 * download2table.jsp
 * downloadoffline.jsp
 * downloadjoin.jsp
 * deleteDownload.jsp
 * deleteTable.jsp
 * 
 * @author yannian.mu
 *
 */
public class TableJoin {
	private static Logger LOG = Logger.getLogger(TableJoin.class);

	public static void addTxt(HttpServletRequest request, HttpServletResponse response,OutputStreamWriter outStream) throws Exception
	{
		SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd");
		String day = fmt.format(new Date());
		Map stormconf = Utils.readStormConfig();
		String store = (String) stormconf.get("higo.download.offline.store") + "/" + day + "/upload_" + java.util.UUID.randomUUID().toString();
		
		Configuration conf=getConf(stormconf);
		FileSystem fs=FileSystem.get(conf);
		if(!fs.exists(new Path(store)))
		{
			fs.mkdirs(new Path(store));
		}
		
		HashMap<String,String> params=new HashMap<String, String>();
		Path outpath=new Path(store,String.valueOf(System.currentTimeMillis()));
		FSDataOutputStream out=fs.create(outpath);
		Upload up=new Upload();
		up.mergerTo(request, response, "utf-8", out, params);
		out.close();
		String rtn=  addTxt(params.get("tableName"), store,params.get("callback"));
		
		
		outStream.append(rtn);
	}
	
	
	public static String addDownload2Table(final String tableName,final String uuid,String callback) throws SQLException, JSONException
	{
		Map stormconf = Utils.readStormConfig();
		String hdpConf = (String) stormconf.get("hadoop.conf.dir");
		String connstr = (String) stormconf.get("higo.download.offline.conn");
		String uname = (String) stormconf.get("higo.download.offline.username");
		String passwd = (String) stormconf.get("higo.download.offline.passwd");
		MySqlConn conn = new MySqlConn(connstr, uname, passwd);
		MysqlInfo info = new MysqlInfo(conn);
		HashMap<String, String> result = info.get(uuid);
		
		String rtn= addTxt(tableName, result.get("storedir"),callback);
		setJoinDownload(tableName, uuid,null);
		DeleteDownload(uuid,null);
		return rtn;
	}
	
	public static void readJoinResult(String uuid, OutputStreamWriter outStream)
	throws SQLException, IOException, JSONException {
		final HashMap<String,String> tableInfo=getTableInfo(uuid);
		JSONObject jsonObj = new JSONObject();

		if(tableInfo==null||tableInfo.isEmpty())
		{
			jsonObj.put("code", "0");
			jsonObj.put("message",  "该表不存在");
			outStream.append(jsonObj.toString());
			return ;
		}
		if(tableInfo.get("status").equals("INDEXING"))
		{
			jsonObj.put("code", "0");
			jsonObj.put("message", "正在创建索引中请稍后");
			outStream.append(jsonObj.toString());
			return ;
		}
		Map stormconf = Utils.readStormConfig();
		
		Configuration conf=getConf(stormconf);
		com.alimama.mdrill.ui.service.AdhocOfflineService.readHiveResult(tableInfo.get("txtStorePath"), outStream, conf);


}
	
	
    private static final ExecutorService       EXECUTE  = ExecutorSerives.EXECUTE;

	public static String addTxt(final String tableName,final String store,final String callback) throws JSONException, SQLException 
	{
		JSONObject jsonObj = new JSONObject();
		final HashMap<String,String> tableInfo=getTableInfo(tableName);
		if(tableInfo==null||tableInfo.isEmpty())
		{
			jsonObj.put("code", "0");
			jsonObj.put("message",  "该表不存在");
			if (callback != null && callback.length() > 0) {
				return callback + "(" + jsonObj.toString() + ")";
			} else {
				return jsonObj.toString();
			}
		}
		if(tableInfo.get("status").equals("INDEXING"))
		{
			jsonObj.put("code", "0");
			jsonObj.put("message", "正在创建索引中请稍后");
			if (callback != null && callback.length() > 0) {
				return callback + "(" + jsonObj.toString() + ")";
			} else {
				return jsonObj.toString();
			}
		}
		
		TableJoin.updatePercent(tableName, "Stage-1 map = 0%,  reduce = 0%", "INDEXING")	;

		jsonObj.put("code", "1");

		EXECUTE.submit(new Runnable() {
			@Override
			public void run() {
				try{
				SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd");
				String day = fmt.format(new Date());
				Map stormconf = Utils.readStormConfig();
				Configuration conf=getConf(stormconf);
				FileSystem fs=FileSystem.get(conf);
				if(!fs.exists(new Path(store)))
				{
					fs.mkdirs(new Path(store));
				}
				
				
				Path txtpath=new Path(tableInfo.get("txtStorePath"));
				if(!fs.exists(txtpath))
				{
					fs.mkdirs(txtpath);
				}
				
				for( FileStatus outpath:fs.listStatus(new Path(store)))
				{
					if(!outpath.isDir())
					{
						fs.rename(outpath.getPath(), new Path(txtpath,outpath.getPath().getName()+"_"+System.currentTimeMillis()));
					}
				}
				fs.delete(new Path(store),true);
				
				Path basepath=new Path(MdrillService.getBasePath());
				FileStatus[] tablelist=fs.listStatus(basepath);
				String solrHome=tablelist[0].getPath().toString();

				for(FileStatus tbl:tablelist){
					if(tbl.isDir()&&fs.exists(new Path(tbl.getPath(),"solr")))
					{
						solrHome=tbl.getPath().toString();
					}
				}
				
				HashSet<String> inputs=new HashSet<String>();
				inputs.add(txtpath.getName());
				

				TableJoin.updateKb(tableName, HadoopUtil.duSize(fs, txtpath));
				                          
				
				String index = (String) stormconf.get("higo.download.offline.store")+ "/" + day + "/tmp_" + java.util.UUID.randomUUID().toString();
				MakeIndex.make(fs, solrHome, conf, "txt", txtpath.getParent().toString(), inputs, "*", tableInfo.get("indexStorePath"), new Path(index), 1, tableInfo.get("splitString"), false, tableInfo.get("colsName"),new updateStatus() {

					@Override
					public void update(int statge, Job job) {
						try {
							TableJoin.LOG.info("update "+tableName+ ",stage:"+statge+",map:"+job.mapProgress()+",reduce:"+job.reduceProgress()+ ":INDEXING");
							String percent="Stage-"+statge+" map = "+(job.mapProgress()*100)+"%,  reduce = "+(job.reduceProgress()*100)+"%";
							TableJoin.updatePercent(tableName, percent, "INDEXING")	;
						} catch (Exception e) {
							TableJoin.LOG.error("updatePercent",e);
						}				
					}

					@Override
					public void finish() {
						try {
							TableJoin.LOG.info("update "+tableName+ ",INDEX");
							TableJoin.updatePercent(tableName, "Stage-2 map = 100%,  reduce = 100%", "INDEX")	;
						} catch (Exception e) {
							TableJoin.LOG.error("updatePercent",e);
						}
					}
				});
			}catch(Exception e){
				TableJoin.LOG.error("make index",e);
				try {
					TableJoin.updatePercent(tableName, "Stage-2 map = 0%,  reduce = 0%", "FAIL")	;
				} catch (Exception e2) {
					TableJoin.LOG.error("updatePercent",e2);
				}
			}
				
			}
		});
		
		if (callback != null && callback.length() > 0) {
			return callback + "(" + jsonObj.toString() + ")";
		} else {
			return jsonObj.toString();
		}
	}
	
	private static Configuration getConf(Map stormconf) {
		String hadoopConfDir = (String) stormconf.get("hadoop.conf.dir");
		String opts = (String) stormconf.get("hadoop.java.opts");
		Configuration conf = new Configuration();
		conf.set("mapred.child.java.opts", opts);
		HadoopUtil.grabConfiguration(hadoopConfDir, conf);
		return conf;
	}

	public static String  updatePercent(String uuid,String percent,String status) throws SQLException
	{
		MySqlConn m_fpsql = getConn();

		Connection conn = m_fpsql.getConn();
		String strSql = "update adhoc_joins set " +
				"percent=?,status=?,lastuptime=? where tableName=? ";
		System.out.println(strSql);
		PreparedStatement m_fps = conn.prepareStatement(strSql);
		try {
			int index=1;
			m_fps.setString(index++, percent);
			m_fps.setString(index++, status);
			m_fps.setTimestamp(index++, new java.sql.Timestamp(System.currentTimeMillis()));
			m_fps.setString(index++, uuid);

			m_fps.executeUpdate();
			String fullstrSql=m_fps.toString();
			return fullstrSql;
		} catch (Exception e) {
			TableJoin.LOG.error("updatePercent"+m_fps.toString(),e);
		}finally{
			m_fps.close();
			m_fpsql.close();
		}
		return "";
	}
	
	public static String  setJoinDownload(String tablename,String uuid,String callback) throws SQLException, JSONException
	{
		JSONObject jsonObj = new JSONObject();

		MySqlConn m_fpsql = getConn();

		Connection conn = m_fpsql.getConn();
		String strSql = "update adhoc_joins set download_uuid=?,lastuptime=? where tableName=? ";
		System.out.println(strSql);
		PreparedStatement m_fps = conn.prepareStatement(strSql);
		try {
			int index=1;
			m_fps.setString(index++, uuid);
			m_fps.setTimestamp(index++, new java.sql.Timestamp(System.currentTimeMillis()));
			m_fps.setString(index++, tablename);

			m_fps.executeUpdate();
			String fullstrSql=m_fps.toString();
			jsonObj.put("code", "1");

			return fullstrSql;
		} catch (Exception e) {
			jsonObj.put("code", "0");

			TableJoin.LOG.error("updatePercent"+m_fps.toString(),e);
		}finally{
			m_fps.close();
			m_fpsql.close();
		}
		
		if (callback != null && callback.length() > 0) {
			return callback + "(" + jsonObj.toString() + ")";
		} else {
			return jsonObj.toString();
		}
	}
	
	public static String  setJoinDelete(String uuid,String callback) throws SQLException, JSONException
	{
		JSONObject jsonObj = new JSONObject();

		MySqlConn m_fpsql = getConn();

		Connection conn = m_fpsql.getConn();
		String strSql = "update adhoc_joins set status='DEL',lastuptime=? where tableName=? ";
		PreparedStatement m_fps = conn.prepareStatement(strSql);
		try {
			int index=1;
			m_fps.setTimestamp(index++, new java.sql.Timestamp(System.currentTimeMillis()));
			m_fps.setString(index++, uuid);

			m_fps.executeUpdate();
			String fullstrSql=m_fps.toString();
			jsonObj.put("code", "1");
			jsonObj.put("__debug", fullstrSql);
		} catch (Exception e) {
			TableJoin.LOG.error("updatePercent"+m_fps.toString(),e);
			jsonObj.put("__debugerror", m_fps.toString());
			jsonObj.put("code", "0");
		}finally{
			m_fps.close();
			m_fpsql.close();
		}
		if (callback != null && callback.length() > 0) {
			return callback + "(" + jsonObj.toString() + ")";
		} else {
			return jsonObj.toString();
		}
	}
	
	public static String  DeleteDownload(String uuid,String callback) throws SQLException, JSONException
	{
		JSONObject jsonObj = new JSONObject();

		MySqlConn m_fpsql = getConn();

		Connection conn = m_fpsql.getConn();
		String strSql = "update adhoc_download set status='DEL',endtime=? where uuid=? ";
		PreparedStatement m_fps = conn.prepareStatement(strSql);
		try {
			int index=1;
			m_fps.setTimestamp(index++, new java.sql.Timestamp(System.currentTimeMillis()));
			m_fps.setString(index++, uuid);

			m_fps.executeUpdate();
			String fullstrSql=m_fps.toString();
			jsonObj.put("code", "1");
			jsonObj.put("__debug", fullstrSql);
		} catch (Exception e) {
			jsonObj.put("code", "0");
			TableJoin.LOG.error("updatePercent"+m_fps.toString(),e);
			jsonObj.put("__debugerror", m_fps.toString());
		}finally{
			m_fps.close();
			m_fpsql.close();
		}
		if (callback != null && callback.length() > 0) {
			return callback + "(" + jsonObj.toString() + ")";
		} else {
			return jsonObj.toString();
		}
	}
	
	public static String  updateKb(String uuid,long sz) throws SQLException
	{
		MySqlConn m_fpsql = getConn();

		Connection conn = m_fpsql.getConn();
		String strSql = "update adhoc_joins set " +
				"resultkb=? where tableName=? ";
		System.out.println(strSql);
		PreparedStatement m_fps = conn.prepareStatement(strSql);
		try {
			int index=1;
			m_fps.setInt(index++, (int)sz/1024);
			m_fps.setString(index++, uuid);

			m_fps.executeUpdate();
			String fullstrSql=m_fps.toString();
			return fullstrSql;
		} catch (Exception e) {
			TableJoin.LOG.error("updatePercent"+m_fps.toString(),e);
		}finally{
			m_fps.close();
			m_fpsql.close();
		}
		return "";
	}
	
	private static MySqlConn getConn()
	{
		SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd");
		String day = fmt.format(new Date());
		Map stormconf = Utils.readStormConfig();
		String connstr = (String) stormconf.get("higo.download.offline.conn");
		String uname = (String) stormconf.get("higo.download.offline.username");
		String passwd = (String) stormconf.get("higo.download.offline.passwd");
		MySqlConn m_fpsql = new MySqlConn(connstr, uname, passwd);
		
		return m_fpsql;
	}
	
	
	public static void main(String[] args) {
		System.out.println(parsePercent("2","Stage-1 map = 100.0%, reduce = 97.49997%",false));
	}
	
	private static Pattern  pat=null;
	public static String parsePercent(String stage,String percent,boolean issuccess)
	{
		if(issuccess)
		{
			return "100%";
		}
//		Stage-2 map = 100%,  reduce = 100%
		if(pat==null)
		{
			pat= Pattern.compile(".*Stage.*[^\\d]*(\\d+)[^\\d]map[^\\d]*(\\d+)[^\\d].*reduce[^\\d]*(\\d+)[^\\d].*");
		}
		
		if(!percent.startsWith("Stage"))
		{
			return "0%";
		}
		
		Integer totalstage=Integer.parseInt(stage);
		Integer currStage=0;
		double map=0;
		double reduce=0;
		
		Matcher mat = pat.matcher(percent);
        while (mat.find()) {
        	currStage=Integer.parseInt(mat.group(1));
        	map=Double.parseDouble(mat.group(2));
        	reduce=Double.parseDouble(mat.group(3));
        }
        
        if(currStage<1)
        {
        	currStage=1;
        }
        
        if(totalstage>0)
        {
        	double result= (100d*(currStage-1)+map*0.5d+reduce*0.5d)/totalstage;
        	return String.valueOf(result)+"%";
        }
		return "100%";
	}
	
	
	public static String getUserTables(String username,int start,int rows,int type,String callback) throws SQLException, JSONException
	{
		SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd");
		Map stormconf = Utils.readStormConfig();
		String connstr = (String) stormconf.get("higo.download.offline.conn");
		String uname = (String) stormconf.get("higo.download.offline.username");
		String passwd = (String) stormconf.get("higo.download.offline.passwd");
		MySqlConn m_fpsql = new MySqlConn(connstr, uname, passwd);

		Connection conn = m_fpsql.getConn();
		Statement stmt = conn.createStatement();
		
		String strsqlJoin="select '1' as source " +
				",tableShowName as tableShowName" +
				",tableName as tableName" +
				",colsShowName as colsShowName" +
				",colsName as colsName" +
				",colsType as colsType" +
				",splitString as splitString" +
				",txtStorePath as txtStorePath" +
				",indexStorePath as indexStorePath" +
				",'0' as extval" +
				",'0' as isfinish" +
				",status as status" +
				",username as username" +
				",createtime as createtime" +
				",lastuptime as lastuptime" +
				",joins as joins" +
				",'2' as stage" +
				",percent as percent" +
				",resultkb as resultkb" +
				" from adhoc_joins where username='"+username.replaceAll("'", "")+"' and status<>'DEL'  ";
		
		
		StringBuffer bufferSql=new StringBuffer();
		if(type==0)//个人中心列表
		{
			String strsqlDownload="select '2' as source " +
			",jobname as tableShowName" +
			",uuid as tableName" +
			",'empty' as colsShowName" +
			",'empty' as colsName" +
			",'empty' as colsType" +
			",'empty' as splitString" +
			",storedir as txtStorePath" +
			",'empty' as indexStorePath" +
			",extval as extval" +
			",isfinish as isfinish" +
			",'INDEX' as status" +
			",username as username" +
			",starttime as createtime" +
			",endtime as lastuptime" +
			",'' as joins" +
			",stage as stage" +
			",percent as percent" +
			",resultkb as resultkb" +
			" from adhoc_download where username='"+username.replaceAll("'", "")+"' and status<>'DEL'  ";
			bufferSql.append("select source,tableShowName,tableName,colsShowName" +
					",colsName,colsType,splitString,txtStorePath,indexStorePath,extval,status,username," +
					"createtime,lastuptime,joins,stage,percent,resultkb");
			bufferSql.append(" from ("+strsqlJoin+" union "+strsqlDownload+") tmp order by tmp.createtime desc limit "+start+","+rows+" ");
		}
		if(type==1)//for join
		{
			bufferSql.append(strsqlJoin+" and status='INDEX' order by createtime desc limit "+start+","+rows+" ");
		}
		
		
		String sql=bufferSql.toString();
		TableJoin.LOG.info("getUserTables:"+sql);
		ResultSet res = stmt.executeQuery(sql);
		JSONObject jsonObj = new JSONObject();
		jsonObj.put("code", "1");
		jsonObj.put("_exehql", sql);
		JSONArray jsonArray = new JSONArray();

		while (res.next()) {
			JSONObject item = new JSONObject();

			item.put("source", res.getString("source"));
			item.put("tableShowName", res.getString("tableShowName"));//展示名称
			item.put("tableName", res.getString("tableName"));//uuid
			item.put("colsShowName", res.getString("colsShowName"));
			item.put("colsName", res.getString("colsName"));
			item.put("colsType", res.getString("colsType"));
			item.put("splitString", res.getString("splitString"));
			item.put("txtStorePath", res.getString("txtStorePath"));
			item.put("indexStorePath", res.getString("indexStorePath"));
			item.put("extval", res.getString("extval"));
			item.put("status", res.getString("status"));
			item.put("username", res.getString("username"));
			item.put("createtime", res.getString("createtime"));
			item.put("lastuptime", res.getString("lastuptime"));
			item.put("joins", res.getString("joins"));
			item.put("stage", res.getString("stage"));
			item.put("percent", res.getString("percent"));
			item.put("resultkb", res.getString("resultkb"));
			boolean issuccess=res.getString("status").equals("INDEX")&&res.getString("extval").equals("0");
			item.put("proccess", parsePercent(res.getString("stage"),res.getString("percent"),issuccess));
			item.put("isActive",String.valueOf(issuccess));
			jsonArray.put(item);
			
		    }
		
		jsonObj.put("list",jsonArray);
		HashMap<String,String> cnt=getUserTablesCount(username, type);
		jsonObj.put("total",cnt.get("cnt"));
		jsonObj.put("total_debug", new JSONObject(cnt));
		m_fpsql.close();
		
	
		if (callback != null && callback.length() > 0) {
			return callback + "(" + jsonObj.toString() + ")";
		} else {
			return jsonObj.toString();
		}	}
	
	public static HashMap<String,String> getUserTablesCount(String username,int type) throws SQLException
	{
		SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd");
		Map stormconf = Utils.readStormConfig();
		String connstr = (String) stormconf.get("higo.download.offline.conn");
		String uname = (String) stormconf.get("higo.download.offline.username");
		String passwd = (String) stormconf.get("higo.download.offline.passwd");
		MySqlConn m_fpsql = new MySqlConn(connstr, uname, passwd);

		Connection conn = m_fpsql.getConn();
		Statement stmt = conn.createStatement();
		
		String strsqlJoin="select count(*) as cnt" +
				" from adhoc_joins where username='"+username.replaceAll("'", "")+"' and status<>'DEL' " ;
		
		StringBuffer bufferSql=new StringBuffer();
		if(type==0)
		{
			String strsqlDownload="select count(*) as cnt" +
			" from adhoc_download where username='"+username.replaceAll("'", "")+"' and status<>'DEL' ";
	
			bufferSql.append("select sum(cnt) as cnt");
			bufferSql.append(" from ("+strsqlJoin+" union "+strsqlDownload+") tmp  limit 10 ");
		}if(type==1)//for join
		{
			bufferSql.append(strsqlJoin+" and status='INDEX' order by createtime desc limit 10 ");
		}
		
		
		String sql=bufferSql.toString();
		ResultSet res = stmt.executeQuery(sql);
		HashMap<String,String> rtn=new HashMap<String,String>();
		rtn.put("_exehql", sql);

		while (res.next()) {
			rtn.put("cnt", String.valueOf(res.getInt("cnt")));
		 }
		m_fpsql.close();
		return rtn;
	}
	
	
	public static HashMap<String,String>  getTableInfo(String uuid) throws SQLException
	{
		SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd");
		String day = fmt.format(new Date());
		Map stormconf = Utils.readStormConfig();
		String hdpConf = (String) stormconf.get("hadoop.conf.dir");
		String connstr = (String) stormconf.get("higo.download.offline.conn");
		String uname = (String) stormconf.get("higo.download.offline.username");
		String passwd = (String) stormconf.get("higo.download.offline.passwd");
		String tmppath = (String) stormconf.get("higo.download.offline.store")
				+ "/" + day + "/tmp_" + java.util.UUID.randomUUID().toString();
		MySqlConn m_fpsql = new MySqlConn(connstr, uname, passwd);

		Connection conn = m_fpsql.getConn();
		Statement stmt = conn.createStatement();

		String sql="select tableShowName,tableName,colsShowName,colsName,colsType,splitString,txtStorePath,indexStorePath,status,username,createtime,lastuptime,joins,percent from adhoc_joins where tableName='"+uuid.replaceAll("'", "")+"' limit 10";
		ResultSet res = stmt.executeQuery(sql);
		HashMap<String,String> rtn=new HashMap<String,String>();
		rtn.put("_exehql", sql);

		while (res.next()) {
			rtn.put("tableShowName", res.getString("tableShowName"));
			rtn.put("txtStorePath", res.getString("txtStorePath"));
			rtn.put("indexStorePath", res.getString("indexStorePath"));
			rtn.put("splitString", res.getString("splitString"));
			rtn.put("colsName", res.getString("colsName"));
			rtn.put("status", res.getString("status"));
			
		    }
		m_fpsql.close();
		
	
		return rtn;
	}
	
	
	
	
	
	
	
	public static String create(String tableShowName,String colsShowName,String splitString,String username,String joins) throws Exception
	{
		JSONObject jsonObj = new JSONObject();

		SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd");
		String day = fmt.format(new Date());
		Map stormconf = Utils.readStormConfig();
		String hdpConf = (String) stormconf.get("hadoop.conf.dir");
		String connstr = (String) stormconf.get("higo.download.offline.conn");
		String uname = (String) stormconf.get("higo.download.offline.username");
		String passwd = (String) stormconf.get("higo.download.offline.passwd");
		String store = (String) stormconf.get("higo.download.offline.store")
				+ "/" + day + "/" + java.util.UUID.randomUUID().toString();
		MySqlConn m_fpsql = new MySqlConn(connstr, uname, passwd);
		
		String tableName=java.util.UUID.randomUUID().toString();
		Connection conn = m_fpsql.getConn();
		String strSql = "insert into adhoc_joins " +
				"(tableShowName,tableName,colsShowName,colsName,colsType,splitString,txtStorePath,indexStorePath,status,username,createtime,lastuptime,joins,percent)" +
				"values" +
				"(?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
		PreparedStatement m_fps = conn.prepareStatement(strSql);
		try {
			int index=1;
			m_fps.setString(index++, tableShowName);
			m_fps.setString(index++, tableName);
			m_fps.setString(index++, colsShowName);
			StringBuffer colsName=new StringBuffer();
			StringBuffer colsType=new StringBuffer();
			String[] cols=colsShowName.split(",");
			String join="";
			HashMap<String,String> colsNames=new HashMap<String, String>();
			for(int i=0;i<cols.length;i++)
			{
				String colname="cols_"+i+"_s";
				colsName.append(join);
				colsName.append(colname);
				colsType.append(join);
				colsType.append("string");
				colsNames.put(cols[i],colname);
				join=",";
			}
			m_fps.setString(index++, colsName.toString());
			m_fps.setString(index++, colsType.toString());
			m_fps.setString(index++, splitString);
			m_fps.setString(index++, store+"/txt");
			m_fps.setString(index++, store+"/index");
			m_fps.setString(index++, "init");
			m_fps.setString(index++, username);
		
			long nowtims=System.currentTimeMillis();
			m_fps.setTimestamp(index++, new java.sql.Timestamp(nowtims));
			m_fps.setTimestamp(index++, new java.sql.Timestamp(nowtims));
			
			StringBuffer joinbuff=new StringBuffer();
			String joinchar="";
			for(String joindesc:joins.split(","))
			{
				String[] arr=joindesc.split(":");
				joinbuff.append(joinchar);
				joinbuff.append(arr[0]);
				joinbuff.append(":");
				joinbuff.append(arr[1]);
				joinbuff.append(":");
				joinbuff.append(colsNames.get(arr[2]));
				joinchar=",";
			}
			m_fps.setString(index++,joinbuff.toString());
			m_fps.setString(index++,"");

			m_fps.executeUpdate();
			jsonObj.put("code", "1");
			jsonObj.put("tableid", tableName);
			jsonObj.put("____debug", m_fps.toString());
		} catch (Exception e) {
			jsonObj.put("____debug2", m_fps.toString());
			jsonObj.put("____debugerror", e.toString());
			jsonObj.put("code", "0");
		}finally{
			m_fps.close();
			m_fpsql.close();
		}
		
		return jsonObj.toString();

	}
}
