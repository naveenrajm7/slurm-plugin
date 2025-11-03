agent {
    slurm {
        cloud 'my-cluster'
        json '''
        {
          "job": {
            "partition": "cpuo",
            "cpus_per_task": 16,
            "required_nodes": ["node1"]
          }
        }
        '''
    }
}