package redis.embedded;

import com.google.common.collect.Lists;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Protocol;
import redis.embedded.exceptions.EmbeddedRedisException;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;


public class RedisCluster implements Redis {
    private final List<Redis> servers = new LinkedList<Redis>();

	private List<Integer> mastersPorts;
	private List<Integer> slavesPorts;

    RedisCluster(
		List<Redis> servers,
		List<Integer> mastersPorts,
		List<Integer> slavesPorts) {
        this.servers.addAll(servers);
        this.mastersPorts = mastersPorts;
        this.slavesPorts = slavesPorts;
    }

    @Override
    public boolean isActive() {
        for(Redis redis : servers) {
            if(!redis.isActive()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void start() throws EmbeddedRedisException, InterruptedException {
        for(Redis redis : servers) {
            redis.start();
        }

        /*
         * Here, we need to manually setup the cluster
         * Because we dont want to add another dependency
         * like ruby's gem create-cluster
         */
		Integer clusterMeetTarget = mastersPorts.get(0);
		Jedis j = null;
		List<String> masterNodeIds = Lists.newArrayList();
		/*
		 * for every master
		 * meet them (except the `seed` master)
		 * and add their slots manually
		 * using pipeline for faster execution
		 */
		try{
			for(Integer i = 0; i < mastersPorts.size(); i++) {
				try {
					j = new Jedis("127.0.0.1", mastersPorts.get(i));

					if(!i.equals(clusterMeetTarget)){
						j.clusterMeet("127.0.0.1", clusterMeetTarget);
					}

					Integer finalI = i;
					Pipeline jp = j.pipelined();

					for (Integer is = 0; is < 16384; is++) {
						if (Integer.valueOf(is % mastersPorts.size()).equals(finalI)) {
							jp.clusterAddSlots(is);
						}
					}
					jp.sync();

					String myId = new String((byte[]) j.sendCommand(Protocol.Command.CLUSTER, "myid"));
					masterNodeIds.add(myId);

				} catch (Exception e) {
					EmbeddedRedisException err = new EmbeddedRedisException(
						"Failed creating master instance at port: "+ mastersPorts.get(i));
					err.setStackTrace(e.getStackTrace());
					throw err;
				} finally {
					if(j!=null) {
						j.close();
						j=null;
					}
				}
			}
		} catch (EmbeddedRedisException e) {
			this.stop();
			throw e;
		}


		/*
		 * Preventing timing issues
		 * Because redis cluster setup need time
		 * it is NOT instantaneous
		 */
		Thread.sleep(mastersPorts.size() * 300);

		int slavesPerShard = slavesPorts.size() / mastersPorts.size();

		/*
		 * meet every slave to the MEET target
		 */
		try {
			for(int i = 0; i < slavesPorts.size(); i++) {
				try {
					j = new Jedis("127.0.0.1", slavesPorts.get(i));
					j.clusterMeet("127.0.0.1", clusterMeetTarget);

					Thread.sleep(1000);

					j.clusterReplicate(masterNodeIds.get(i / slavesPerShard));
				} catch (Exception e) {
					EmbeddedRedisException err = new EmbeddedRedisException(
						"Failed creating slave instance at port: "+ slavesPorts.get(i));
					err.setStackTrace(e.getStackTrace());
					throw err;
				} finally {
					if(j!=null) {
						j.close();
						j=null;
					}
				}
			}
		} catch (EmbeddedRedisException e) {
			this.stop();
			throw e;
		}


		/*
		 * also prevent timing issues
		 */
		Thread.sleep(500);
    }

    @Override
    public void stop() throws EmbeddedRedisException {
        for(Redis redis : servers) {
            redis.stop();
        }
    }

    @Override
    public List<Integer> ports() {
        List<Integer> ports = new ArrayList<Integer>();
        ports.addAll(serverPorts());
        return ports;
    }

    public List<Integer> serverPorts() {
        List<Integer> ports = new ArrayList<Integer>();
        ports.addAll(mastersPorts);
        ports.addAll(slavesPorts);
        return ports;
    }

	public List<Redis> getServers() {
		return servers;
	}

    public static RedisClusterBuilder builder() {
        return new RedisClusterBuilder();
    }
}
