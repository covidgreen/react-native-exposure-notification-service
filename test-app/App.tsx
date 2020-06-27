import React from 'react';
import { StyleSheet, Text, View, ScrollView, Button } from 'react-native';

import useContactTracing from './useContactTracing'
import RNIOS11DeviceCheck from 'react-native-ios11-devicecheck';

export default function App() {
  const tracing = useContactTracing()

  const color = tracing.status && tracing.status.state === 'active'
  ? 'green' : 'red'
 
  return (
    <View style={styles.container}>
      <ScrollView>
          <Text style={styles.heading}>Apple/Google Contact Tracing Test App</Text>
          <View style={{flexDirection: 'row'}}>
            <Text>Status: </Text>
            <Text style={{color: `${color}`}}>{'\u2B24'}</Text>
          </View>
          <Text>Last Result: {JSON.stringify(tracing.result, null, 2)}</Text>
          <Text>Last Event: {JSON.stringify(tracing.lastEvent, null, 2)}</Text>
          <View style={styles.hspace}/>
          <Button onPress={tracing.canSupport} 
            title='Can Support' />   
          <Button onPress={tracing.isSupported} 
            title='Is Supported' />     
          <Button onPress={tracing.isAuthorised} 
            title='Is Authorised' />   
         <Button onPress={tracing.authoriseExposure} 
            title='Authorise' />        
          <Button onPress={tracing.exposureEnabled} 
            title='Is Enabled' /> 
          <Button onPress={tracing.configure} 
            title='Configure' /> 
          <Button onPress={tracing.start} 
            title='Start' />
           <Button onPress={tracing.getStatus} 
            title='Get Status' />
          <Button onPress={tracing.stop} 
            title='Stop' /> 
          <Button onPress={tracing.checkExposure} 
            title='Check Exposure' />  
           <Button onPress={tracing.getLogData} 
            title='Get Log Data' />  
          <Button onPress={tracing.getDiagnosisKeys} 
            title='Get Keys' />                                                                
         <Button onPress={tracing.deleteAllData} 
            title='Delete All Data' />   
          <Button onPress={tracing.deleteExposureData} 
            title='Delete Exposure Data' />      
         <Button onPress={tracing.triggerUpdate} 
            title='Trigger Update (android only)' />                
          <View style={styles.hspace}/>
          <View style={styles.hspace}/>
          <Text style={styles.heading}>App Log:</Text>
          <Text>{JSON.stringify(tracing.log, null, 2)}</Text>
          <View style={styles.hspace}/>
          <View style={styles.hspace}/>
          <Text style={styles.heading}>Event Log (reverse chronological order):</Text>
          <Text>{JSON.stringify(tracing.eventLog, null, 2)}</Text>
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    padding: 10,
  },
  heading: {
    fontWeight: 'bold',
    fontSize: 16,
    marginBottom: 5
  },
  hspace: {
    margin: 5,
  },
  hr: {
    marginTop: 3,
    marginBottom: 3,
    borderColor: 'black',
    borderBottomWidth: 1
  }
});
