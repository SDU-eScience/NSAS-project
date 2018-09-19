/**
 * (c) 2018 SDU eScienceCenter
 * All rights reserved
 */
 
import { Cloud } from "../../authentication/SDUCloudObject";
import { genericFailureNotification } from "../UtilityFunctions";
import { RECEIVE_APPLICATIONS, SET_APPLICATIONS_LOADING, TO_APPLICATIONS_PAGE, UPDATE_APPLICATIONS_PER_PAGE, UPDATE_APPLICATIONS } from "../Reducers/Applications";

const receiveApplications = (applications) => ({
    type: RECEIVE_APPLICATIONS,
    applications
});

export const fetchApplications = () =>
    Cloud.get("/hpc/apps").then(({ response }) => {
        response.sort((a, b) =>
            a.prettyName.localeCompare(b.prettyName)
        );
        return receiveApplications(response);
    }).catch(() => genericFailureNotification());

export const setLoading = (loading) => ({
    type: SET_APPLICATIONS_LOADING,
    loading
});

export const toPage = (pageNumber) => ({
    type: TO_APPLICATIONS_PAGE,
    pageNumber
});

export const updateApplications = (applications) => ({
    type: UPDATE_APPLICATIONS,
    applications
});

export const updateApplicationsPerPage = (applicationsPerPage) => ({
    type: UPDATE_APPLICATIONS_PER_PAGE,
    applicationsPerPage
});
