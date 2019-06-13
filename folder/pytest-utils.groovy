#!groovy

// Add the name of a failing stage to this list
failedStages = [];
// git commit sha variable
gitCommitSHA = '';
// git checkout variables
gitVars = [:];
// SHA used to notify bitbucket/stash about build status
bitbucketNotifySha = '';
// This is used in sendStatusNotifications and typically summarizes which steps failed
statusDetails = '';

def sayHi(purpose) {
    echo "Run Type: ${params.RUN_TYPE}, Node Name: $NODE_NAME, Purpose: ${purpose}"
}

def echoDebug(debug) {
    echo "Debug: ${debug}"
    return debug
}

// Clears and fetches new public keys for the specified host.
def updatePublicHostKeys(hostnameOrIp) {
    sh "ssh-keygen -f '/var/lib/jenkins/.ssh/known_hosts' -R \"${hostnameOrIp}\""
    sh "ssh-keyscan -H ${hostnameOrIp} >> ~/.ssh/known_hosts"
}

def runTests(boardName, boardIp, configFiles, testStrings = "") {
    /* Does the following:
        * start virual environment
        * setup packages for tests
        * run pytest with unit IP and configFiles
    */
    sh "#!/bin/bash\n \
        echo $PWD 
        && ls"

    // junit(allowEmptyResults: true, testResults: '**/tmp/*_result.xml')
}

def listTests(configFiles) {
    // sh "#!/bin/bash\n \
    //     && source ./setup.sh \
    //     && echo python3 -m runtest \
    //         --ip 0.0.0.0 \
    //         --configs ${configFiles} \
    //         --list > ./tmp/test_list.txt \
    //     && deactivate"
    // return readFile('./tmp/test_list').trim()

    return "#!/bin/bash\n \
        && source ./setup.sh \
        && python3 -m runtest \
            --ip 0.0.0.0 \
            --configs ${configFiles} \
            --list \
        && deactivate".execute().text.split('\n')
}

def generateTestNode(setup) {
    return {
        node {
            stage("${setup.'name'}: checkout on ${NODE_NAME}") {
                echo "Checkout here"
            }

            //////////////////////////////    
            // TODO: Impletement update //
            //////////////////////////////

            stage("${setup.'name'} tests") {
                dir('folder') {
                    pwd()
                    try {
                        sayHi("Running ${setup.'configs'}");
                        runTests(setup.'name', setup.'ip', setup.'configs', setup.'tests');
                        currentBuild.result = 'SUCCESS';
                    } catch(err) {
                        currentBuild.result = 'FAILURE';
                        failedStages.add("${setup.'name'} tests");
                        //sendMyNotifications();
                        throw err;
                    }
                }
            }
        }
    }
}

return this