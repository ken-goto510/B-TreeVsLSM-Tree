package com.example.benchmark.service.runner;

import com.example.benchmark.model.BenchmarkProperties;
import com.example.benchmark.model.DbEngine;
import org.springframework.stereotype.Component;

@Component
public class CassandraRunner extends AbstractCassandraRunner {

    private final BenchmarkProperties props;

    public CassandraRunner(BenchmarkProperties props) { this.props = props; }

    @Override public DbEngine getEngine()        { return DbEngine.CASSANDRA; }
    @Override protected String contactHost()     { return props.getCassandra().getHosts(); }
    @Override protected int    contactPort()     { return props.getCassandra().getPort(); }
    @Override protected String localDatacenter() { return props.getCassandra().getDatacenter(); }
}
