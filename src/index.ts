import { NitroModules } from 'react-native-nitro-modules'
import type { LocationSession as LocationSessionSpec } from './specs/location-session.nitro'

export const LocationSession =
  NitroModules.createHybridObject<LocationSessionSpec>('LocationSession')