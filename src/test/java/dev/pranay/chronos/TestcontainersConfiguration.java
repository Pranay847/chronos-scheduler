package dev.pranay.chronos;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	/**
	 * Mongo for integration tests.
	 *
	 * <p>Pinned to {@code mongo:7} rather than {@code mongo:latest} so a CI run six months from
	 * now tests the same server this was written against — and so it matches docker-compose.yml,
	 * because a green test suite against a different major version than production is a
	 * particularly annoying way to be wrong.
	 *
	 * <p><b>{@code withReplicaSet()} is required, and its absence is silent.</b> In Testcontainers
	 * 2.x the replica set is opt-in: {@code org.testcontainers.mongodb.MongoDBContainer} starts a
	 * plain standalone mongod unless you ask for one. The older
	 * {@code org.testcontainers.containers.MongoDBContainer} — still shipped in the same jar —
	 * configured {@code --replSet} and ran {@code rs.initiate()} on its own, so guidance written
	 * against it says to add nothing at all. Following that here produces a standalone that
	 * passes every Phase 1 test and only fails in Phase 9, when change streams report "not
	 * supported on standalone" and the obvious suspect is the change-stream code.
	 *
	 * <p>Note what does <em>not</em> work: {@code withCommand("--replSet", "rs0")}.
	 * {@code withCommand} <em>replaces</em> the container's command rather than extending it, and
	 * it does nothing about {@code rs.initiate()}, so the set is configured but never initiated.
	 *
	 * <p>{@code JobApiIntegrationTest#mongoIsARealReplicaSet} asserts {@code setName} is present
	 * precisely so this fails at setup rather than eight phases later.
	 */
	@Bean
	@ServiceConnection
	MongoDBContainer mongoDbContainer() {
		return new MongoDBContainer(DockerImageName.parse("mongo:7")).withReplicaSet();
	}

}
