import jenkins.model.Jenkins

def path = '${WORKLOAD_FOLDER}/${WORKLOAD_JOB}'
def item = Jenkins.get().getItemByFullName(path)
if (!item) return "not found: ${path}"
def names = item.getItems()*.name
return item.class.name + ' branches=' + names
