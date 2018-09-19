/**
 * (c) 2018 SDU eScienceCenter
 * All rights reserved
 */
 
export const dateToString = (date: number) => new Date(date).toLocaleString().replace(/\./g, ":");
