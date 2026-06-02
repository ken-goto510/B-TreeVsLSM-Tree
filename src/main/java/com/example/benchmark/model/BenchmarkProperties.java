package com.example.benchmark.model;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "benchmark")
public class BenchmarkProperties {

    private Jdbc mysql      = new Jdbc();
    private Jdbc postgresql = new Jdbc();
    private Jdbc cockroachdb = new Jdbc();
    private Cql  cassandra  = new Cql();
    private Cql  scylladb   = new Cql();
    private RocksDb rocksdb = new RocksDb();

    public static class Jdbc {
        private String url;
        private String username;
        private String password;
        // getters / setters
        public String getUrl()      { return url; }
        public void   setUrl(String v) { url = v; }
        public String getUsername() { return username; }
        public void   setUsername(String v) { username = v; }
        public String getPassword() { return password; }
        public void   setPassword(String v) { password = v; }
    }

    public static class Cql {
        private String hosts      = "localhost";
        private int    port       = 9042;
        private String datacenter = "datacenter1";
        public String getHosts()       { return hosts; }
        public void   setHosts(String v)  { hosts = v; }
        public int    getPort()        { return port; }
        public void   setPort(int v)   { port = v; }
        public String getDatacenter()  { return datacenter; }
        public void   setDatacenter(String v) { datacenter = v; }
    }

    public static class RocksDb {
        private String path = "./rocksdb-data";
        public String getPath()       { return path; }
        public void   setPath(String v) { path = v; }
    }

    public Jdbc    getMysql()       { return mysql; }
    public void    setMysql(Jdbc v) { mysql = v; }
    public Jdbc    getPostgresql()  { return postgresql; }
    public void    setPostgresql(Jdbc v) { postgresql = v; }
    public Jdbc    getCockroachdb() { return cockroachdb; }
    public void    setCockroachdb(Jdbc v) { cockroachdb = v; }
    public Cql     getCassandra()   { return cassandra; }
    public void    setCassandra(Cql v) { cassandra = v; }
    public Cql     getScylladb()    { return scylladb; }
    public void    setScylladb(Cql v) { scylladb = v; }
    public RocksDb getRocksdb()     { return rocksdb; }
    public void    setRocksdb(RocksDb v) { rocksdb = v; }
}
