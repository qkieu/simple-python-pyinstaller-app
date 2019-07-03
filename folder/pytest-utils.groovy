#!groovy

// Add the name of a failing stage to this list
failedStages = [];
// This is used in sendStatusNotifications and typically summarizes which steps failed
statusDetails = '';
// git checkout variables
gitVars = [:];
// Tracker for how many unit completed
totalUnits = 0;
completedUnits = 0;

// Print purpose of the current build and name of node
def sayHi(purpose) {
    echo "Run Type: ${params.RUN_TYPE}, Node Name: $NODE_NAME, Purpose: ${purpose}"
}

// Print any debugging information
def echoDebug(debug) {
    echo "Debug: ${debug}"
}

// Clears and fetches new public keys for the specified host.
def updatePublicHostKeys(hostnameOrIp) {
    sh "ssh-keygen -f '/var/lib/jenkins/.ssh/known_hosts' -R \"${hostnameOrIp}\""
    sh "ssh-keyscan -H ${hostnameOrIp} >> ~/.ssh/known_hosts"
}

// Send emails and Slack notification
def sendMyNotifications() {
    sayHi('Sending Notifications')
    if (failedStages.size() > 0) {
        def tempString = 'Failed Stages: ';
        failedStages.each {
            tempString += "\"$it\", ";
        }
        statusDetails = tempString[0..-3]; // Remove trailing ', '
    }
    sendStatusNotifications(this, gitVars.GIT_COMMIT, setup.usersToNotify, statusDetails);
}

// Setup build settings
def setBuildSetup() {
    try {
        def defaultUsersToNotify = setup.usersToNotify
        timeout(time: 2, unit: 'MINUTES') {
            setup = input(message: 'Build Settings', ok: 'Build',
                            parameters: [
                                string(name: 'scmBranchName',
                                        defaultValue: setup.scmBranchName,
                                        description: 'Change the branch if needed'),
                                string(name: 'scmRemoteRefs',
                                        defaultValue: setup.scmRemoteRefs,
                                        description: 'Change the remote refs if needed',
                                        trim: true),
                                text(name: 'usersToNotify',
                                        defaultValue: setup.usersToNotify.join("\n"),
                                        description: 'Who should this build notify?'),
                                string(name: 'buildNode',
                                        defaultValue: setup.buildNode,
                                        description: 'Change the build node if needed',
                                        trim: true),
                                booleanParam(name: 'buildImage',
                                            defaultValue: setup.buildImage,
                                            description: 'Do you want to build new ARM images?'),
                                booleanParam(name: 'runUnitTests',
                                            defaultValue: setup.runUnitTests,
                                            description: 'Do you want to run farm tests?'),
                                booleanParam(name: 'archiveImage',
                                            defaultValue: setup.archiveImage,
                                            description: 'Do you want to build new ARM images?'),        
                                booleanParam(name: 'cleanCheckout',
                                            defaultValue: setup.cleanCheckout,
                                            description: 'Do you want a clean check-out? (otherwise toolchain/binaries from previous build may be used)'),
                                string(name: 'imageFromFTP',
                                        defaultValue: setup.imageFromFTP,
                                        description: 'Set image to grab from FTP instead',
                                        trim: true),
                            ]);
            
            setup.usersToNotify = setup.usersToNotify?.trim() ? setup.usersToNotify.split("\\r?\\n") : defaultUsersToNotify;
        }
    } catch(err) { // timeout reached
        echoDebug("Timeout Reached; continuing")
    }
}

// Checkout from set branch and refspec
def ospreyCheckout() {
    try {
        def exten = null;
        if (setup.cleanCheckout) {
            exten = [[$class: 'GitLFSPull'],
                        [$class: 'CleanCheckout']];
        }
        else {
            exten = [[$class: 'GitLFSPull']];
        }
        gitVars = checkout([
            $class: 'GitSCM',
            branches: [[name: setup.scmBranchName]],
            extensions: exten,
            userRemoteConfigs: [[credentialsId: 'stash-ssh',
                                    refspec: setup.scmRemoteRefs,
                                    url: 'ssh://git@stash.sesg.fluke.com:7999/fvp/mantis-top.git']]
        ]);

        step([$class: 'StashNotifier', commitSha1: "${gitVars.GIT_COMMIT}"]);

        sh 'git reset --hard';
        sh 'git submodule foreach --recursive "git reset --hard"';
        sh 'if [ -d module-firmware/copper/Nautilus/NautilusModule/release@tmp ]; then\
                rmdir --ignore-fail-on-non-empty module-firmware/copper/Nautilus/NautilusModule/release@tmp;\
            fi;\
            if [ -d module-firmware/copper/Nautilus/NautilusModule/release ]; then\
                rmdir --ignore-fail-on-non-empty --parents module-firmware/copper/Nautilus/NautilusModule/release;\
            fi;'
        sh 'if [ -d module-firmware/fiber/pon-otdr/bin@tmp ]; then\
                rmdir --ignore-fail-on-non-empty module-firmware/fiber/pon-otdr/bin@tmp;\
            fi;\
            if [ -d module-firmware/fiber/pon-otdr/bin ]; then\
                rmdir --ignore-fail-on-non-empty --parents module-firmware/fiber/pon-otdr/bin;\
            fi;'
        sh 'if [ -d otdr-tools/otdr/projects/modb@tmp ]; then\
                rmdir --ignore-fail-on-non-empty otdr-tools/otdr/projects/modb@tmp;\
            fi;\
            if [ -d otdr-tools/otdr/projects/modb ]; then\
                rmdir --ignore-fail-on-non-empty --parents otdr-tools/otdr/projects/modb;\
            fi;'

        // For general use
        sh './cgit checkout-profile user-devel';
        // For building OSPrey Firmware
        sh './cgit checkout-profile module-firmware-fiber';

        // We don't want to use previously-built local versions of firmware
        dir('module-firmware/copper/Nautilus/NautilusModule/release') {
            sh 'if [ -e Makefile ]; then make --quiet clean; fi';
        }
        dir('module-firmware/fiber/pon-otdr/bin') {
            sh 'if [ -e Makefile ]; then make --quiet clean; fi';
        }
        sh 'rm -rf build-pkgs/dev-files';

        // Clean checkout
        if (setup.cleanCheckout) {
            sh 'git clean -fdx';
            sh 'git submodule foreach --recursive "git clean -fdx"';
        }
    }
    catch(err) {
        currentBuild.result = 'FAILURE';
        failedStages.add('Checkout');
        // If we have a checkout failure, exit here
        sendMyNotifications();
        throw err;
    }
}

def ospreyBuild() {
    sayHi('Building');
    // Get build limits
    desiredJobs = sh(returnStdout: true, script: 'nproc').trim();
    desiredLoad = desiredJobs.toInteger() * 1.5;
    buildLimits = "--jobs=${desiredJobs} --load-average=${desiredLoad}";
    timeoutMult = 1;
    if (desiredJobs.toInteger() < 16) { // If less than 16, extend timeouts a bit
        timeoutMult = 1.5;
    }

    // Initialize build tasks
    def tasks = [:]

    // Checkout files on the node
    ospreyCheckout();

    // Set environment for terminal and build type
    def myEnv = ['TERM=xterm'];
    if (env.JOB_NAME =~ '/daily/') {
        myEnv.add('BUILDTYPE=nightly');
    }
    try {
        // Fetch packages with set environment
        withEnv(myEnv) {
            timeout(time: 2 * timeoutMult, unit: 'MINUTES') {
                sh 'make --quiet pkg-fetch';
                pkgsFetched = true;
            }
        }
    }
    catch(err) {
        currentBuild.result = 'FAILURE';
        failedStages.add('Fetch packages');
        pkgsFetched = false;
        throw err;
    }

    // Build ARM
    if(setup.buildImage) {
        tasks['ARM'] = {
            sayHi('Building ARM')
            // Build
            try {
                // Make Versiv images with set environment
                withEnv(myEnv) {
                    timeout(time: 30 * timeoutMult, unit: 'MINUTES') {
                        if (pkgsFetched == true) {
                            sh 'make --quiet user-fonts1 user-fonts2';
                            sh "make ${buildLimits} --quiet update";
                            currentBuild.result = 'SUCCESS';
                            armBuilt = true;
                        }
                    }
                }
                // Stash images for deploy during unit testing
                stash(name: 'images', includes: 'images/v1/*, images/v2/*');
                
                // Archive image if set
                if (setup.archiveImage) {
                    archiveArtifacts(artifacts: 'images/v1/*, images/v2/*');   
                }
            }
            catch(err) {
                currentBuild.result = 'FAILURE';
                failedStages.add('Build ARM');
                armBuilt = false;
                throw err;
            }
        }
    }

    // Build result dump
    if(setup.runUnitTests) {
        tasks['x86'] = {
            sayHi('Building x86')
            // Build
            try {
                withEnv(['TERM=xterm', 'QT5DIR=/opt/qt-5.6.1']) {
                    timeout(time: 30 * timeoutMult, unit: 'MINUTES') {
                        if (pkgsFetched == true) {
                            // Make x86 toolchain
                            def build = "make ${buildLimits} --quiet -f Makefile.x86 toolchain";                            
                            sh build;

                            // Build result dump
                            sh "make -C user/x86-linux-debug ${buildLimits} --quiet resultdump" // Just build result dump for now

                            // Stash result dump and necessary libraries
                            stash(name: 'resultdump', includes: 'user/x86-linux-debug/resultdump');
                            stash(name: 'libcross', includes: 'user/x86-linux-debug/cicada/libcrossplatform/*.so' );
                            stash(name: 'tclib', includes: 'tools/x86/x86_64/toolchain/lib/*.so*');
                            stash(name: 'tslib', includes: 'tools/x86/x86_64/toolchain/x86_64-timesys-linux-gnu/lib/libasan.so.2, \
                                                            tools/x86/x86_64/toolchain/x86_64-timesys-linux-gnu/lib/libubsan.so.0*');

                            currentBuild.result = 'SUCCESS';
                        }
                    }
                }
            }
            catch(err) {
                currentBuild.result = 'FAILURE';
                failedStages.add('Build x86');
                throw err;
            }
        }
    }

    // Try running ARM and resultdump builds in parallel
    try {
        parallel(tasks);
    }
    catch(err) {
        currentBuild.result = 'FAILURE';
        // If we have any build failure(s), exit here
        sendMyNotifications();
        throw err;
    }
}

def runTests(boardName, boardIp, configFiles, testStrings = "") {
    /* Does the following:
        * start virual environment
        * setup packages for tests
        * run pytest with unit IP and configFiles
    */
    sh "#!/bin/bash\n cd jenkins-pytest\
        && source ./setup.sh \
        && python3 -m runtests \
            --name ${boardName} \
            --ip ${boardIp} \
            --configs ${configFiles} \
            --run=\"${testStrings}\" \
        && deactivate"

    // store xml results on build run
    junit(allowEmptyResults: true, testResults: '**/jenkins-pytest/tmp/results/test_result_*.xml')
}

def listTests(configFiles) {
    // Activate virtual environment beforehand to not get setup printouts later
    sh "cd jenkins-pytest \
        && source ./setup.sh \
        && deactivate"

    // Run --list with configuration files and other filler parameters
    return sh(
        script: "#!/bin/bash\n cd jenkins-pytest \
                    && source ./venv/bin/activate \
                    && python3 -m runtests \
                    --ip 0.0.0.0 \
                    --configs ${configFiles} \
                    --list \
                    && deactivate",
        returnStdout: true
    ).trim().split('\n') // Return a list of test WITH EMPTY ('')
                         // test for every configuration file used
}

def generateTestNode(nodeSetup) {
    return {
        node("linux") { // NEED TO CHANGE TO "linux && nodeSetup.'name' once we have enough node
            sayHi("Running ${nodeSetup.'configs'}");
            // Checkout
            ospreyCheckout();

            // Add unit ip address to allowed hosts
            updatePublicHostKeys(nodeSetup.'ip');

            // Set permission for private access key to be used
            sh "chmod 600 ./tools/cwuser_dsa"

            // Deploy new image if new image was built
            if(setup.buildImage || !setup.imageFromFTP.isEmpty()) {
                try {
                    if(setup.buildImage) {
                        unstash(name: 'images');
                    } else {
                        sh "mkdir -p ./images/ \
                            && wget -A 'update.ci*' -r -np -nH --cut-dirs 5 http://fww.tc.fluke.com/~cicada/Software/mantis-builds/testing/${setup.imageFromFTP}/ -P ./images/"
                    }
                    sh "chmod +x ./jenkins-pytest/deploy_update.sh"
                    sh "MANTIS_HOST=${nodeSetup.ip} ./jenkins-pytest/deploy_update.sh"
                } catch(err) {
                    failedStages.add("${nodeSetup.'name'} deploy")
                }
            }

            // Run unit tests for each unit
            try {
                unstash(name: 'resultdump');
                unstash(name: 'libcross');
                unstash(name: 'tclib');
                unstash(name: 'tslib');
                runTests(nodeSetup.'name', nodeSetup.'ip', nodeSetup.'configs', nodeSetup.'tests');
                currentBuild.result = 'SUCCESS';
                completedUnits++;
                // Send final notification once all test units are completed
                if (completedUnits == totalUnits) {
                    sendMyNotifications();
                }
            } catch(err) {
                currentBuild.result = 'FAILURE';
                failedStages.add("${nodeSetup.'name'} tests");
                sendMyNotifications();
                throw err;
            }
        }
    }
}

def startTests(taskMap) {
    def test_tasks = [:];

    totalUnits = taskMap.size();

    taskMap.each { taskname, tasksettings -> 
        def testsetup = [:]
        testsetup.'name'    = taskname;
        testsetup.'ip'      = tasksettings.ip;
        testsetup.'tests'   = tasksettings.containsKey('tests') ? tasksettings.tests : '';
        testsetup.'configs' = tasksettings.configs;
        
        test_tasks[taskname] = utils.generateTestNode(testsetup);
    }

    try {
        parallel(test_tasks);
    }
    catch(err) {
        failedStages.add("Start tests");
        currentBuild.result = 'FAILURE';
        sendMyNotifications();
        throw err;
    }
}

return this