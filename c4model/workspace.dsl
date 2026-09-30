workspace {

    model {
        participant = person "Participant" "A participant in the study."
        researcher = person "Researcher" "A researcher running the study."
        
        
        facepsySystem = softwareSystem "FacePsy System" "Opportunistic facial behavior data collection system." {
                facepsyApp = container "FacePsy App" "" "Android" {
                    hiddencam = component "Opportunistic background camera sensing" "" "Camera API"
                    firebaseConn = component "Firebase Connector" "" "Firebase SDK"
                    appUsageEventLogger = component "Usage Event Listener" "" "Accessibility Service, screen broadcasts"
                    cogGames = component "Cognitive Tasks and Survey" "" ""
                    notification = component "Daily Notification" "" "Firebase Cloud Messaging"
                    behaviorSensing = component "Facial Behavior Sensing" "" "TensorFlow Lite, ML Kit"
                    # surveyWebview = component "Survey View" "" "Web View"
                }
                dashboardApp = container "Compliance Dashboard" "Not part of this repository." "Python Plotly"

        }
        
        qualtricsSystem = softwareSystem "Qualtrics" "External online survey platform (survey links configured in Firestore)."
        firebaseSystem = softwareSystem "Google Firebase" "An app development platform." {
            fcm = container "Firebase Cloud Messaging" "" "Cloud solution for messages and notifications."
        }
        
        bigquerySystem = softwareSystem "Google Big Query" "External data warehouse used for analysis; not part of this repository."
        
        participant -> facepsySystem "Uses"
        participant -> facepsyApp "Uses"
        
        researcher -> facepsySystem "Uses"
        researcher -> dashboardApp "Uses"
        
        facepsySystem -> qualtricsSystem "FacePsy redirects survey request to Qualtrics"
        facepsySystem -> firebaseSystem "App Backend"
        facepsySystem -> bigquerySystem "Analytics Backend"
        
        dashboardApp -> bigquerySystem "Visualize data"
        dashboardApp -> qualtricsSystem "Survey data"
        facepsyApp -> firebaseSystem "App Backend"
        firebaseSystem -> bigquerySystem "Sync"
        facepsyApp -> qualtricsSystem "User takes survey"
        
        firebaseConn -> firebaseSystem "API"
        
        notification -> fcm "Cloud Messaging API"
        
        appUsageEventLogger -> hiddencam "Triggers Data Collection"
        hiddencam -> behaviorSensing "Schedule background processing"
        behaviorSensing -> firebaseConn "Store to Firebase"
        
        cogGames -> firebaseConn "Store to Firebase"
        cogGames -> qualtricsSystem "Uses"
        
        

    }

    views {
        systemContext facepsySystem "SystemContext" {
            include *
            autoLayout lr
        }

        container facepsySystem "SystemContainer" "Facial behavior sensing mobile application" {
            include *
            autoLayout lr
        }
        
        component facepsyApp "SystemComponent" {
            include *
            autoLayout lr
        }
        
        theme default
    }
    
}