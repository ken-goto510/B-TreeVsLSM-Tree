package com.example.benchmark.service.runner;

import com.example.benchmark.model.BenchmarkProperties;
import com.example.benchmark.model.DbEngine;
import org.springframework.stereotype.Component;

@Component
public class ScyllaDbRunner extends AbstractCassandraRunner {

    private final BenchmarkProperties props;

    public ScyllaDbRunner(BenchmarkProperties props) { this.props = props; }

    @Override public DbEngine getEngine()        { return DbEngine.SCYLLADB; }
    @Override protected String contactHost()     { return props.getScylladb().getHosts(); }
    @Override protected int    contactPort()     { return props.getScylladb().getPort(); }
    @Override protected String localDatacenter() { return props.getScylladb().getDatacenter(); }
}
