/**
 * (c) 2018 SDU eScienceCenter
 * All rights reserved
 */
 
import React from "react";
import { DefaultLoading } from "../LoadingIcon/LoadingIcon";
import { getParentPath, iconFromFilePath } from "../../UtilityFunctions";
import { Link } from "react-router-dom";
import { Cloud } from "../../../authentication/SDUCloudObject"
import { favorite, toLowerCaseAndCapitalize, getFilenameFromPath } from "../../UtilityFunctions";
import { updatePageTitle } from "../../Actions/Status";
import { setAllLoading, fetchFavorites, fetchRecentAnalyses, fetchRecentFiles, receiveFavorites } from "../../Actions/Dashboard";
import { connect } from "react-redux";
import "./Dashboard.scss";
import "../Styling/Shared.scss";
import { Card, List, Icon } from "semantic-ui-react";
import moment from "moment";
import { FileIcon } from "../UtilityComponents";

class Dashboard extends React.Component {
    constructor(props) {
        super(props);
        const { favoriteFiles, recentFiles, recentAnalyses, activity } = this.props;
        this.props.updatePageTitle();
        if (!favoriteFiles.length && !recentFiles.length && !recentAnalyses.length && !activity.length) {
            this.props.setAllLoading(true);
        }
        this.props.fetchFavorites();
        this.props.fetchRecentFiles();
        this.props.fetchRecentAnalyses();
        //this.props.fetchRecentActivity();
    }


    render() {
        const { favoriteFiles, recentFiles, recentAnalyses,
            favoriteLoading, recentLoading, analysesLoading } = this.props;
        const favoriteOrUnfavorite = (filePath) =>
            this.props.receiveFavorites(favorite(favoriteFiles, filePath, Cloud).filter(file => file.favorited));
        return (
            <React.StrictMode>
                <Card.Group className="mobile-padding">
                    <DashboardFavoriteFiles
                        files={favoriteFiles}
                        isLoading={favoriteLoading}
                        favorite={(filePath) => favoriteOrUnfavorite(filePath)}
                    />
                    <DashboardRecentFiles files={recentFiles} isLoading={recentLoading} />
                    <DashboardAnalyses analyses={recentAnalyses} isLoading={analysesLoading} />
                </Card.Group>
            </React.StrictMode>
        );
    }
}

const DashboardFavoriteFiles = ({ files, isLoading, favorite }) => {
    const noFavorites = files.length || isLoading ? "" : (<h3 className="text-center">
        <small>No favorites found.</small>
    </h3>);
    const filesList = files.map((file, i) =>
        (<List.Item key={i} className="itemPadding">
            <List.Content floated="right">
                <Icon name="star" color="blue" onClick={() => favorite(file.path)} />
            </List.Content>
            <ListFileContent path={file.path} type={file.type} link={false} />
        </List.Item>)
    );

    return (
        <Card>
            <Card.Content>
                <Card.Header>
                    Favorite files
                </Card.Header>
                <DefaultLoading loading={isLoading} />
                {noFavorites}
                <List divided size={"large"}>
                    {filesList}
                </List>
            </Card.Content >
        </Card >)
};

const ListFileContent = ({ path, type, link }) =>
    <React.Fragment>
        <List.Content>
            <FileIcon name={type === "FILE" ? iconFromFilePath(path) : "folder"} link={link} color="grey" />
            <Link to={`files/${type === "FILE" ? getParentPath(path) : path}`}>
                {getFilenameFromPath(path)}
            </Link>
        </List.Content>
    </React.Fragment>


const DashboardRecentFiles = ({ files, isLoading }) => {
    const noRecents = files.length || isLoading ? "" : (<h3 className="text-center">
        <small>No recent files found</small>
    </h3>);
    const filesList = files.sort((a, b) => b.modifiedAt - a.modifiedAt).map((file, i) => (
        <List.Item key={i} className="itemPadding">
            <List.Content floated="right">
                <List.Description>{moment(new Date(file.modifiedAt)).fromNow()}</List.Description>
            </List.Content>
            <ListFileContent path={file.path} type={file.type} link={file.link} />
        </List.Item>
    ));

    return (
        <Card>
            <Card.Content>
                <Card.Header>
                    Recently used files
                </Card.Header>
                <DefaultLoading loading={isLoading} />
                {noRecents}
                <List divided size={"large"}>
                    {filesList}
                </List>
            </Card.Content>
        </Card>);
};

const DashboardAnalyses = ({ analyses, isLoading }) => (
    <Card>
        <Card.Content>
            <Card.Header>
                Recent Analyses
            </Card.Header>
            <DefaultLoading loading={isLoading} />
            {isLoading || analyses.length ? null :
                (<h3 className="text-center">
                    <small>No analyses found</small>
                </h3>)
            }
            <List divided size={"large"}>
                {analyses.map((analysis, index) =>
                    <List.Item key={index} className="itemPadding">
                        <List.Content floated="right">
                            {toLowerCaseAndCapitalize(analysis.state)}
                        </List.Content>
                        <List.Icon name={statusToIconName(analysis.state)} color={statusToColor(analysis.state)} />
                        <List.Content>
                            <List.Header>
                                <Link to={`/analyses/${analysis.jobId}`}>{analysis.appName}</Link>
                            </List.Header>
                        </List.Content>
                    </List.Item>
                )}
            </List>
        </Card.Content>
    </Card>
);

const statusToIconName = (status) => status === "SUCCESS" ? "check" : "x";
const statusToColor = (status) => status === "SUCCESS" ? "green" : "red";

const mapDispatchToProps = (dispatch) => ({
    updatePageTitle: () => dispatch(updatePageTitle("Dashboard")),
    setAllLoading: (loading) => dispatch(setAllLoading(loading)),
    fetchFavorites: () => dispatch(fetchFavorites()),
    fetchRecentFiles: () => dispatch(fetchRecentFiles()),
    fetchRecentAnalyses: () => dispatch(fetchRecentAnalyses()),
    fetchRecentActivity: () => dispatch(fetchRecentActivity()),
    receiveFavorites: (files) => dispatch(receiveFavorites(files))
});

const mapStateToProps = (state) => {
    const {
        favoriteFiles,
        recentFiles,
        recentAnalyses,
        activity,
        favoriteLoading,
        recentLoading,
        analysesLoading,
        activityLoading,
    } = state.dashboard;
    return {
        favoriteFiles,
        recentFiles,
        recentAnalyses,
        activity,
        favoriteLoading,
        recentLoading,
        analysesLoading,
        activityLoading,
        favoriteFilesLength: favoriteFiles.length // Hack to ensure re-rendering
    };
};

export default connect(mapStateToProps, mapDispatchToProps)(Dashboard)
