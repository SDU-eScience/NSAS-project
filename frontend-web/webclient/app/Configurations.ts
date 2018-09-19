/**
 * (c) 2018 SDU eScienceCenter
 * All rights reserved
 */
 
import * as Uppy from "uppy";

interface TusConfig { endpoint: string, headers: any}
export const tusConfig:TusConfig = {
    endpoint: "https://cloud.sdu.dk/api/tus",
    headers: {}
};
