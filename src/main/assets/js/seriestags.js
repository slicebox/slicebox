(function () {
    'use strict';
}());

angular.module('slicebox.seriestags', ['ngRoute'])

.config(function($routeProvider) {
    $routeProvider.when('/seriestags', {
        templateUrl: '/assets/partials/seriestags.html',
        controller: 'SeriesTagsCtrl'
    });
})

.controller('SeriesTagsCtrl', function($scope, $http, openDeleteEntitiesModalFunction, openAddEntityModal, openUpdateModal, openTagSeriesModal, sbxToast) {
    // Initialization
    $scope.actions =
        [
            {
                name: 'Rename',
                action: $scope.updateSeriesTag,
                requiredSelectionCount: 1
            },
            {
                name: 'Delete',
                action: openDeleteEntitiesModalFunction('/api/metadata/seriestags/', 'series tag(s)')
            }
        ];

    if (!$scope.uiState.seriesTagsTableState) {
        $scope.uiState.seriesTagsTableState = {};
    }

    $scope.callbacks = {};

    // Scope functions
    $scope.loadSeriesTagsPage = function(startIndex, count, orderByProperty, orderByDirection, filter) {
        var loadUrl = '/api/metadata/seriestags?startindex=' + startIndex + '&count=' + count;
        if (orderByProperty) {
            loadUrl = loadUrl + '&orderby=' + orderByProperty;

            if (orderByDirection === 'ASCENDING') {
                loadUrl = loadUrl + '&orderascending=true';
            } else {
                loadUrl = loadUrl + '&orderascending=false';
            }
        }

        if (filter) {
            loadUrl = loadUrl + '&filter=' + encodeURIComponent(filter);
        }

        var loadPromise = $http.get(loadUrl);

        loadPromise.error(function(error) {
            sbxToast.showErrorMessage('Failed to load series tags: ' + error);
        });

        return loadPromise;
    };

    $scope.addSeriesTagButtonClicked = function() {
        return openAddEntityModal('addSeriesTagModalContent.html', 'AddSeriesTagModalCtrl', '/api/metadata/seriestags', 'Series Tag', $scope.callbacks.seriesTagsTable);
    };

    $scope.updateSeriesTag = function(seriesTags) {
        if (seriesTags.length >= 1) {
            var seriesTag = seriesTags[0];
            return openUpdateModal('/api/metadata/seriestags/' + seriesTag.id, seriesTag, 'Series Tag', 'name');
        } else {
            sbxToast.showErrorMessage('Cannot rename multiple series tags at once');
        }
    };

})

.controller('AddSeriesTagModalCtrl', function($scope, $mdDialog) {

    // Scope functions
    $scope.addButtonClicked = function() {
        $mdDialog.hide({ id: -1, name: $scope.name });
    };

    $scope.cancelButtonClicked = function() {
        $mdDialog.cancel();
    };
});