/**
 * (c) 2018 SDU eScienceCenter
 * All rights reserved
 */
 
import React from "react";
import { DefaultLoading } from "../LoadingIcon/LoadingIcon";
import { WebSocketSupport, toLowerCaseAndCapitalize, shortUUID } from "../../UtilityFunctions"
import { updatePageTitle } from "../../Actions/Status";
import { Cloud } from "../../../authentication/SDUCloudObject";
import { Container, Table, Responsive } from "semantic-ui-react";
import { Link } from "react-router-dom";
import * as Pagination from "../Pagination";
import { connect } from "react-redux";
import "../Styling/Shared.scss";
import { setLoading, fetchAnalyses } from "../../Actions/Analyses";

class Analyses extends React.Component {
    constructor(props) {
        super(props);
        this.state = {
            reloadIntervalId: -1
        };
        this.props.dispatch(updatePageTitle("Analyses"));
    }

    componentWillMount() {
        this.getAnalyses(false);
        let reloadIntervalId = setInterval(() => {
            this.getAnalyses(true)
        }, 10000);
        this.setState({ reloadIntervalId });
    }

    componentWillUnmount() {
        clearInterval(this.state.reloadIntervalId);
    }

    getAnalyses(silent) {
        const { dispatch, analysesPerPage, pageNumber } = this.props;
        if (!silent) {
            dispatch(setLoading(true))
        }
        dispatch(fetchAnalyses(analysesPerPage, pageNumber));
    }

    render() {
        const { dispatch, analysesPerPage } = this.props;
        const noAnalysis = this.props.analyses.length ? "" : (<h3 className="text-center">
            <small>No analyses found.</small>
        </h3>);

        return (
            <React.StrictMode>
                <DefaultLoading loading={this.props.loading} />
                <WebSocketSupport />
                {noAnalysis}
                <Table basic="very" unstackable className="mobile-padding">
                    <Table.Header>
                        <Table.Row>
                            <Table.HeaderCell>App Name</Table.HeaderCell>
                            <Table.HeaderCell>Job Id</Table.HeaderCell>
                            <Table.HeaderCell>State</Table.HeaderCell>
                            <Responsive as={Table.HeaderCell} minWidth={768}>Status</Responsive>
                            <Responsive as={Table.HeaderCell} minWidth={768}>Started at</Responsive>
                            <Responsive as={Table.HeaderCell} minWidth={768}>Last updated at</Responsive>
                        </Table.Row>
                    </Table.Header>
                    <AnalysesList analyses={this.props.analyses} />
                    <Table.Footer>
                        <Table.Row>
                            <Table.Cell colSpan="6" textAlign="center">
                                <Pagination.Buttons
                                    totalPages={this.props.totalPages}
                                    currentPage={this.props.pageNumber}
                                    toPage={(pageNumber) => dispatch(fetchAnalyses(analysesPerPage, pageNumber))}
                                />
                            </Table.Cell>
                        </Table.Row>
                    </Table.Footer>
                </Table>
                <Pagination.EntriesPerPageSelector
                    entriesPerPage={this.props.analysesPerPage}
                    onChange={(pageSize) => dispatch(fetchAnalyses(pageSize, 0))}
                    content="Results per page"
                />
            </React.StrictMode>
        )
    }
}

const AnalysesList = ({ analyses, children }) => {
    if (!analyses && !analyses[0].name) {
        return null;
    }
    const analysesList = analyses.map((analysis, index) => {
        const jobIdField = analysis.status === "COMPLETE" ?
            (<Link to={`/files/${Cloud.jobFolder}/${analysis.jobId}`}>{analysis.jobId}</Link>) : analysis.jobId;
        return (
            <Table.Row key={index} className="gradeA row-settings">
                <Table.Cell>
                    <Link to={`/applications/${analysis.appName}/${analysis.appVersion}`}>
                        {analysis.appName}@{analysis.appVersion}
                    </Link>
                </Table.Cell>
                <Table.Cell>
                    <Link to={`/analyses/${jobIdField}`}>
                        <span title={jobIdField}>{shortUUID(jobIdField)}</span>
                    </Link>
                </Table.Cell>
                <Table.Cell>{toLowerCaseAndCapitalize(analysis.state)}</Table.Cell>
                <Responsive as={Table.Cell} minWidth={768}>{analysis.status}</Responsive>
                <Responsive as={Table.Cell} minWidth={768}>{formatDate(analysis.createdAt)}</Responsive>
                <Responsive as={Table.Cell} minWidth={768}>{formatDate(analysis.modifiedAt)}</Responsive>
            </Table.Row>)
    });
    return (
        <Table.Body>
            {analysesList}
        </Table.Body>)
};

const formatDate = (millis) => {
    // TODO Very primitive
    let d = new Date(millis);
    return `${pad(d.getDate(), 2)}/${pad(d.getMonth() + 1, 2)}/${pad(d.getFullYear(), 2)} ${pad(d.getHours(), 2)}:${pad(d.getMinutes(), 2)}:${pad(d.getSeconds(), 2)}`
};

const pad = (value, length) => (value.toString().length < length) ? pad("0" + value, length) : value;

const mapStateToProps = ({ analyses }) => ({ loading, analyses, analysesPerPage, pageNumber, totalPages } = analyses);
export default connect(mapStateToProps)(Analyses);
