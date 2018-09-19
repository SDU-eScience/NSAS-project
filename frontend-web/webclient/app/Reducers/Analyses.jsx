/**
 * (c) 2018 SDU eScienceCenter
 * All rights reserved
 */
 
export const SET_ANALYSES_LOADING = "SET_ANALYSES_LOADING";
export const RECEIVE_ANALYSES = "RECEIVE_ANALYSES";
export const SET_ANALYSES_PAGE_SIZE = "SET_ANALYSES_PAGE_SIZE";

const analyses = (state = [], action) => {
    switch (action.type) {
        case RECEIVE_ANALYSES: {
            return { ...state, analyses: action.analyses, analysesPerPage: action.analysesPerPage, pageNumber: action.pageNumber, totalPages: action.totalPages, loading: false };
        }
        case SET_ANALYSES_LOADING: {
            return { ...state, loading: action.loading };
        }
        case SET_ANALYSES_PAGE_SIZE: {
            return { ...state, analysesPerPage: action.analysesPerPage };
        }
        default: {
            return state;
        }
    }
}

export default analyses;
