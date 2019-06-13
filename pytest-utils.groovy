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
        && source ./setup.sh \
        && python3 -m runtest \
            --name ${boardName} \
            --ip ${boardIp} \
            --configs ${configFiles} \
            --run=${testStrings} \
        && deactivate"

    // store xml results on build run.  The 'Test Result' is shown on the
    // build number
    junit(allowEmptyResults: true, testResults: '**/tmp/*_result.xml')
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
        node("linux && ${setup.'name'}") {
        stage("${setup.'name'}: checkout on ${NODE_NAME}") {
            // Checkout without clean
            // utils.doCheckout(false)

            // Since we might be moving boards about or doing full reflashes
            // with them, update the public keys from the board before we
            // start talking to it.
            updatePublicHostKeys(setup.'ip');
        }

        //////////////////////////////    
        // TODO: Impletement update //
        //////////////////////////////

        stage("${setup.'name'} tests") {
            try {
                sayHi("Running ${taskconfigs}");
                runTests(setup.'name', setup.'ip', setup.'configs', setup.'tests');
                currentBuild.result = 'SUCCESS';
            } catch(err) {
                currentBuild.result = 'FAILURE';
                failedStages.add("${setup.'name'} tests");
                sendMyNotifications();
                throw err;
            }
        }
    }
}

// def doCheckout(def doClean = false)
// {
//     gitVars = checkout([
//         $class: 'GitSCM',
//         branches: [[name: scmBranchName]],
//
//         /////////////////////////////
//         // TODO: Change repository //
//         /////////////////////////////
//
//         userRemoteConfigs: [[credentialsId: 'stash-ssh',
//                              refspec: scmRemoteRefs,
//                              url: 'ssh://git@stash.sesg.fluke.com:7999/fnet/heartland.git']] 
//     ])

//     // reset all submodules back to their last committed state
//     sh 'git submodule foreach \'git reset --hard\''
//     // update / pulls submodules
//     sh './cgit checkout-profile devel'

//     if (doClean) {
//         echoDebug("Clean Build")
//         sh 'git submodule foreach git clean -fdx'
//         sh 'PRODUCT=ciq2 make clean'
//         sh 'PRODUCT=ciq2 make -f Makefile.x86 clean'
//     }

//     // used in sendMyNotifications
//     if (params.RUN_TYPE == 'daily') {
//         bitbucketNotifySha = gitVars.GIT_COMMIT;
//     } else {
//         bitbucketNotifySha = params.PULL_REQUEST_FROM_HASH;
//     }

//     // used in isUpdateNeeded
//     // checkout hash from daily run is refs/remotes/origin/master
//     // checkout hash from prcheck run is refs/remotes/origin/pull-requests/<PR ID>/merge
//     gitCommitSHA = gitVars.GIT_COMMIT;
// }



// def isUpdateNeeded(functionalTestBoardIp)
// {
//     // TODO: add '--json' parameter to 'sysinfo' for parsing SHA value
//     boardVersion = sh (
//         script: "ssh root@${functionalTestBoardIp} \"/opt/bin/hwiz sysinfo\"",
//         returnStdout: true
//     ).trim()
//     updateSHA = boardVersion.replace("[\n\r]","").split(' ')[1]
//     echoDebug("gitCommitSHA: ${gitCommitSHA}, unitUpdateSHA: ${updateSHA}")
//     if (gitCommitSHA != updateSHA) {
//         echoDebug("gitCommitSHA does _not_ match unitUpdateSHA; update needed")
//         return true  // update
//     } else {
//         echoDebug("gitCommitSHA matches unitUpdateSHA; _no_ update needed")
//         return false  // don't update
//     }
// }

// def sendMyNotifications(failedStages)
// {
//     if (failedStages.size() > 0) {
//         def tempString = 'Failed Stages: ';
//         failedStages.each {
//             tempString += "\"$it\", ";
//         }
//         statusDetails = tempString[0..-3]; // Remove trailing ', '
//     }

//     echoDebug("bitbucketNotifySha: ${bitbucketNotifySha}, usersToNotify: ${usersToNotify}")
//     sendStatusNotifications(this, bitbucketNotifySha, usersToNotify, statusDetails);
// }

return this