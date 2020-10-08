import { useEffect, useState } from 'react'
import { NativeEventEmitter } from 'react-native'
import config from './config'
import ExposureNotificationModule from 'react-native-exposure-notification-service'
const emitter = new NativeEventEmitter(ExposureNotificationModule)

export default function useContactTracing() {
    const [status, setStatus] = useState({})
    const [log, setLog] = useState({})
    const [eventLog, setEventLog] = useState([])
    const [lastEvent, setLastEvent] = useState({})

    const [result, setResult] = useState("")

    useEffect(() => {
        loading()
        function handleEvent(ev) {
            setLastEvent(ev)
            eventLog.unshift(ev)
            setEventLog([...eventLog])
            console.log('event', ev) 
            ev.onStatusChanged && setStatus(ev.onStatusChanged)
        }        

       let subscription = emitter.addListener('exposureEvent', handleEvent)


        async function tryStart() {
            const can = await ExposureNotificationModule.canSupport()
            const supported = await ExposureNotificationModule.isSupported()

            if (can && supported) {
                start()
            } else {
                setResult('cannot start: not supported')
            }
        }

        tryStart()

        return () => {

            subscription.remove()
            emitter.removeListener('exposureEvent', handleEvent)
        }
    }, [])


    const loading = () => {
        setLastEvent({})
        setResult('')
        setResult(`Loading, please wait...`)
    }
   
    const start = async () => {
        loading()
        try {
            await ExposureNotificationModule.configure(config)
            const result =  await ExposureNotificationModule.start()
            setResult(`started: ${result}`)
        } catch(e) {
            setResult(`start - Error: ${e}`)
        }
        
    }

    const stop = async () => {
        loading()
        try {
            const result = await ExposureNotificationModule.stop()
            setResult(`stopped: ${result}`)
        } catch(e) {
            setResult(`stop - Error: ${e}`)
        }
        
    }  
    
    const configure = async() => {
        loading()
        try {
            await ExposureNotificationModule.configure({
                exposureCheckFrequency: 15,
                serverURL: 'your-url',
                authToken: 'your-token',
                refreshToken: 'your-token',
                storeExposuresFor: 14,
                fileLimit: 100,
                version: 'x.y.z',
                notificationTitle: 'Title',
                notificationDesc: 'Description',
                callbackNumber: '',
                analyticsOptin: false,
                debug: true
            })
            setResult(`exposureEnabled: called`)
        } catch(e) {
            setResult(`configure - Error: ${e}`)
        }
    }

    const checkExposure = async () => {
        loading()
        try {
            ExposureNotificationModule.checkExposure(false, true)
            setResult(`checkExposure: called`)
        } catch(e) {
            setResult(`checkExposure - Error: ${e}`)
        }
    }

    const getDiagnosisKeys = async () => {
        loading()
        try {
            const result = await ExposureNotificationModule.getDiagnosisKeys()
            setResult(`getDiagnosisKeys: ${JSON.stringify(result)}`)
        } catch(e) {
            setResult(`getDiagnosisKeys - Error: ${e}`)
        }
    }

    const exposureEnabled = async () => {
        loading()
        try {
            const result = await ExposureNotificationModule.exposureEnabled()
            setResult(`exposureEnabled: ${result}`)
        } catch(e) {
            setResult(`exposureEnabled - Error:${e}`)
        }
    }

    const isAuthorised = async () => {
        loading()
        try {
            const result = await ExposureNotificationModule.isAuthorised()
            setResult(`isAuthorised: ${result}`)
        } catch(e) {
            setResult(`isAuthorised - Error:${e}`)
        }
        
    }

    const authoriseExposure = async () => {
        loading()
        try {
            const result = await ExposureNotificationModule.authoriseExposure()
            setResult(`authoriseExposure: ${result}`)
        } catch(e) {
            setResult(`authoriseExposure - Error:${e}`)
        }
    }

    const isSupported = async() => {
        loading()
        try {
            const result =  await ExposureNotificationModule.isSupported()
            setResult(`isSupported: ${result}`)
        } catch(e) {
            setResult(`isSupported - Error:${e}`)
        }
    }

    const canSupport = async() => {
        loading()
        try {
            const result =  await ExposureNotificationModule.canSupport()
            setResult(`canSupport: ${result}`)
        } catch(e) {
            setResult(`canSupport - Error:${e}`)
        } 
    }

    const deleteAllData = async() => {
        loading()
        try {
            const result =  await ExposureNotificationModule.deleteAllData()

            setResult(`deleteAllData: ${result}`)
        } catch(e) {
            setResult(`deleteAllData - Error:${e}`)
        }
    }   

    const deleteExposureData = async() => {
        loading()
        try {
            const result =  await ExposureNotificationModule.deleteExposureData()
            setResult(`deleteExposureData: ${result}`)
        } catch(e) {
            setResult(`deleteExposureData - Error:${e}`)
        }
    }   

    const getLogData = async() => {
        loading()
        try {
            const logData =  await ExposureNotificationModule.getLogData()

            setLog(logData)
            setResult(`getLogData: see app log below`)
        } catch (e) {
            setResult(`getLogData - Error:${e}`)
        }
    } 

    const getStatus = async() => {
        loading()
        try {
            const status = await ExposureNotificationModule.status()

            setResult(`getStatus: ${status.state}`)
        } catch(e) {
            setResult(`getStatus - Error:${e}`)
        }
    } 

    const triggerUpdate = async() => {
        loading()
        const response = await ExposureNotificationModule.triggerUpdate()

        setResult(`triggerUpdate: ${response}`)
    } 


    return {
        status,
        eventLog,
        lastEvent,
        result,
        log,
        start,
        getStatus,
        stop,
        configure,
        checkExposure,
        getDiagnosisKeys,
        exposureEnabled,
        isAuthorised,
        authoriseExposure,
        isSupported,
        deleteAllData,
        deleteExposureData,
        getLogData,
        canSupport,
        triggerUpdate,
    }
}


