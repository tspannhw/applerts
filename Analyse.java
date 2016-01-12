package flink.datastream.applerts;
/**
 * Created by root on 19/12/15.
 */

import org.apache.flink.api.common.JobExecutionResult;
import org.apache.flink.api.common.functions.FilterFunction;
import org.apache.flink.api.common.functions.RichFlatMapFunction                ;
import org.apache.flink.api.java.utils.ParameterTool                            ;
import org.apache.flink.streaming.api.datastream.DataStream                     ;
import org.apache.flink.streaming.api.datastream.DataStreamSink                 ;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment    ;
import org.apache.flink.streaming.api.functions.sink.SinkFunction               ;
import org.apache.flink.util.Collector                                          ;
import org.apache.flink.api.java.operators.FlatMapOperator                      ;
import javax.json.Json              ;
import javax.json.JsonArray         ;
import javax.json.JsonObject        ;
import javax.json.JsonReader        ;
import java.io.StringReader         ;
import java.text.SimpleDateFormat   ;
import java.util.Date               ;
import java.util.Calendar           ;
import java.util.List               ;
import java.util.Arrays             ;
import java.sql.DriverManager       ;
import java.sql.Connection          ;
import java.sql.Statement           ;
import java.sql.ResultSet           ;
import java.sql.SQLException        ;

import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;

import flink.datastream.applerts.AppPOJO;

public class Analyse {
    final static Logger analyse_app_log = Logger.getLogger(Analyse.class);
    public static void main(String[] args) throws Exception {
        String propertiesFile;
        if(args.length == 0 )
            propertiesFile = "/root/packages/config.ini";
            else
            propertiesFile = args[0].trim();
        final ParameterTool parameter = ParameterTool.fromPropertiesFile(propertiesFile);
        try {
            PropertyConfigurator.configure(parameter.get("log4j"));
        }catch (NullPointerException e){
            analyse_app_log.error(e);
            System.out.print(e);
            System.exit(100);
        }

        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.getConfig().setGlobalJobParameters(parameter);

        final int maxEventDelay = 60;
        String rmhost   = parameter.getRequired("resourcemanagerhost");
        String rmport   = parameter.getRequired("resourcemanagerport");
        String url      = "http://" + rmhost + ":" + rmport + "/ws/v1/cluster/apps";

        DataStream<FlinkJSONObject> live = env.addSource(new HTTPJSONStream(url.trim(),maxEventDelay)).name("YARN-Applications-Stream");

        DataStream<AppPOJO> in_stream =
                live.flatMap(new ReadJSONOutput()).keyBy("id").filter(new FilterFunction<AppPOJO>() {
                    @Override
                    public boolean filter(AppPOJO record) throws Exception {

                        List<String> appStates = Arrays.asList("KILLED", "FINISHED", "FAILED", "SUCCEEDED");
                        SimpleDateFormat date_fmt = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");
                        Calendar cal = Calendar.getInstance();
                        cal.set(Calendar.HOUR_OF_DAY, 0);
                        cal.set(Calendar.MINUTE, 0);
                        cal.set(Calendar.SECOND, 0);
                        cal.set(Calendar.MILLISECOND, 0);
                        long todayMilSecs = 1L;//(cal.getTimeInMillis());

                        boolean exists = false;

                        String  dbhost  = parameter.get("dbhost").trim()                        ;
                        String  dbport  = parameter.get("dbport").trim()                        ;
                        String  dbclass = parameter.get("dbdriverclass").trim()                 ;
                        String  dbname  = parameter.get("dbname").trim()                        ;
                        String  dbuser  = parameter.get("dbuser").trim()                        ;
                        String  dbpwd   = parameter.get("dbpassword").trim()                    ;
                        String  dburl   = "jdbc:postgresql://"+dbhost+":"+dbport+"/"+dbname     ;

                        Connection  conn    = null                                              ;
                        Statement   stmt    = null                                              ;
                        ResultSet   rs      = null                                              ;

                        try {
                            Class.forName(dbclass);
                        } catch (ClassNotFoundException e) {
                            analyse_app_log.error("Filter Phase : JDBC Driver Class Not Found"+e);
                            System.exit(104);
                        }

                        try{
                            conn = DriverManager.getConnection(dburl,dbuser,dbpwd);
                        }catch (Exception e){
                            analyse_app_log.error("Filter Phase : Connection Error"+e);
                            System.exit(105);
                        }

                        String  query = "select exists(select 1 from applerts_db where app_id ='"+record.getId().trim()+"')";
                        try{
                            stmt    = conn.createStatement();
                            rs      = stmt.executeQuery(query);
                            while (rs.next()){
                                exists = rs.getBoolean(1);
                            }
                        }catch (Exception e){
                            analyse_app_log.error("Filter Phase : Error During verification"+e);;
                            System.exit(106);
                        }

                        try{
                            conn.close();
                        }catch (Exception e){
                            exists=false;
                            analyse_app_log.error("Filter Phase : Connection Close Error"+e);;
                            System.exit(107);
                        }

                        if (!exists && Long.valueOf(record.getStartedTime().trim()) > todayMilSecs && appStates.contains(record.getFinalStatus())){
                            analyse_app_log.info(record.toString());
                            return true;
                        }
                        else
                        {return false;}
                    }
                }).name("Process-Completed-Records");

        in_stream.addSink(new SinkFunction<AppPOJO>() {
            @Override
            public void invoke(AppPOJO appPOJO) throws Exception {

                String  dbhost  = parameter.get("dbhost").trim()                        ;
                String  dbport  = parameter.get("dbport").trim()                        ;
                String  dbclass = parameter.get("dbdriverclass").trim()                 ;
                String  dbname  = parameter.get("dbname").trim()                        ;
                String  dbuser  = parameter.get("dbuser").trim()                        ;
                String  dbpwd   = parameter.get("dbpassword").trim()                    ;
                String  dburl   = "jdbc:postgresql://"+dbhost+":"+dbport+"/"+dbname     ;

                List<String>    appStates   = Arrays.asList("KILLED", "FAILED")         ;

                Connection  conn = null                                                 ;
                Statement   stmt = null                                                 ;

                String alerted  = "false"                                               ;
                try {
                    Class.forName(dbclass);
                } catch (ClassNotFoundException e) {
                    analyse_app_log.error("Load Phase : JDBC Driver Class Not Found"+e);
                    System.exit(104);
                }

                try{
                    conn = DriverManager.getConnection(dburl,dbuser,dbpwd);
                }catch (Exception e){
                    analyse_app_log.error("Load Phase : Connection Error"+e);
                    System.exit(105);
                }

                SimpleDateFormat date_fmt   = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss")                       ;
                String stime                = date_fmt.format(new Date(Long.valueOf(appPOJO.getStartedTime()))) ;
                String ftime                = date_fmt.format(new Date(Long.valueOf(appPOJO.getFinishedTime())));

                appPOJO.setStartedTime(stime);
                appPOJO.setFinishedTime(ftime);

                if(appStates.contains(appPOJO.getFinalStatus())){
                    String  getEmailId = "select email from alerts where isenabled='enabled' and app_name='"+appPOJO.getName().trim()+"';";
                    alerted = verifyForAlert(parameter,appPOJO);
                }

                String query ="insert into applerts_db" +
                        "(app_id ,app_name ,app_user ,app_final_status ,app_type ,app_url ,app_start_time ,app_finish_time ,app_elapsed_time ,app_diagnostics ,app_alert_state) " +
                        "values("+
                        "'"+ appPOJO.getId()                +   "'," +
                        "'"+ appPOJO.getName()              +   "'," +
                        "'"+ appPOJO.getUser()              +   "'," +
                        "'"+ appPOJO.getFinalStatus()       +   "'," +
                        "'"+ appPOJO.getApplicationType()   +   "'," +
                        "'"+ appPOJO.getTrackingUrl()       +   "'," +
                        "'"+ appPOJO.getStartedTime()       +   "'," +
                        "'"+ appPOJO.getFinishedTime()      +   "'," +
                        "'"+ appPOJO.getElapsedTime()       +   "'," +
                        "'"+ appPOJO.getDiagnostics()       +   "'," +
                        "'"+ alerted                        +   "'"  +
                        ");";

                try{
                    stmt = conn.createStatement();
                    stmt.execute(query);
                }catch (Exception e){
                    analyse_app_log.error("Load Phase : Error during inserting data"+e);
                    System.exit(106);
                }



                try{
                    conn.close();
                }catch (Exception e){
                    conn=null;
                    analyse_app_log.error("Load Phase : Error during closing Connection"+e);
                    System.exit(107);
                }
            }
        }).name("Load-To-DB");
        JobExecutionResult jer = env.execute("Applerts");
    }

    public static String verifyForAlert(ParameterTool parameter,AppPOJO appPOJO){

        String  alerted = "false";
        return alerted;
    }

    public static class ReadJSONOutput extends RichFlatMapFunction<FlinkJSONObject, AppPOJO> {
        final static Logger rjo_app_log = Logger.getLogger(ReadJSONOutput.class);
        @Override
        public void flatMap(FlinkJSONObject value, Collector<AppPOJO> out) throws Exception {

            JsonReader jsonReader   = Json.createReader(new StringReader(value.jsonObject)) ;
            JsonObject jsonObject   = jsonReader.readObject()                               ;
            JsonObject appsObj      = jsonObject.getJsonObject("apps")                      ;
            JsonArray appArray      = appsObj.getJsonArray("app")                           ;

            for (int apps = 0; apps < appArray.size(); apps++) {
                JsonObject appObj   = appArray.getJsonObject(apps)  ;
                String track_url    = ""                            ;
                try{
                    track_url = appObj.getString("trackingUrl");
                }catch (NullPointerException npe){
                    track_url="NO TRACKING URL AT PROCESSING TIME";
                    rjo_app_log.info("NO TRACKING URL AT PROCESSING TIME : "+ appObj.getString("id"));
                }
                out.collect(
                            new AppPOJO(
                                    appObj.getString("id")                  ,
                                    appObj.getString("name")                ,
                                    appObj.getString("user")                ,
                                    appObj.getString("finalStatus")         ,
                                    track_url                               ,
                                    appObj.getString("applicationType")     ,
                                    appObj.get("startedTime").toString()    ,
                                    appObj.get("finishedTime").toString()   ,
                                    appObj.get("elapsedTime").toString()    ,
                                    appObj.getString("diagnostics")
                                        )
                            );
            }
        }
    }
}