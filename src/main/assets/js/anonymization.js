(function () {
   'use strict';
}());

angular.module('slicebox.anonymization', ['ngRoute'])

.config(function($routeProvider) {
  $routeProvider.when('/anonymization', {
    templateUrl: '/assets/partials/anonymization.html',
    controller: 'AnonymizationCtrl'
  });
})

.controller('AnonymizationCtrl', function($scope, $http, openMessageModal, openDeleteEntitiesModalFunction, openTagSeriesModalFunction, sbxToast) {
    // Initialization
    $scope.actions =
        [
            {
                name: 'Delete',
                action: openDeleteEntitiesModalFunction('/api/anonymization/keys/', 'anonymization key(s)')
            },
            {
                name: 'Export',
                action: $scope.exportToCsv
            },
            {
                name: 'Tag Series',
                action: openTagSeriesModalFunction('/api/anonymization/keys/')
            }            
        ];

    if (!$scope.uiState.anonymizationTableState) {
        $scope.uiState.anonymizationTableState = {};
    }

    $scope.callbacks = {};

    // Scope functions
    $scope.loadAnonymizationKeyPage = function(startIndex, count, orderByProperty, orderByDirection, filter) {
        var loadUrl = '/api/anonymization/keys?startindex=' + startIndex + '&count=' + count;
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
            sbxToast.showErrorMessage('Failed to load anonymization keys: ' + error);
        });

        return loadPromise;
    };

    $scope.keySelected = function(key) {
        $scope.uiState.selectedKey = key;

        if ($scope.callbacks.keyValuesTable) {
            $scope.callbacks.keyValuesTable.reset();
        }
    };

    $scope.loadKeyValues = function(startIndex, count, orderByProperty, orderByDirection, filter) {
        if ($scope.uiState.selectedKey === null) {
            return [];
        }

        var keyValuesPromise = $http.get('/api/anonymization/keys/' + $scope.uiState.selectedKey.id + '/keyvalues').then(function(data) {
            if (filter) {
                var filterLc = filter.toLowerCase();
                data.data = data.data.filter(function (keyValue) {
                    var tagPathCondition = keyValue.tagPath.indexOf(filterLc) >= 0;
                    var valueCondition = keyValue.value.toLowerCase().indexOf(filterLc) >= 0;
                    var anonCondition = keyValue.anonymizedValue.toLowerCase().indexOf(filterLc) >= 0;
                    return tagPathCondition || valueCondition || anonCondition;
                });
            }
            if (orderByProperty) {
                if (!orderByDirection) {
                    orderByDirection = 'ASCENDING';
                }
                return data.data.sort(function compare(a,b) {
                    return orderByDirection === 'ASCENDING' ?
                        a[orderByProperty] < b[orderByProperty] ? -1 : a[orderByProperty] > b[orderByProperty] ? 1 : 0 :
                        a[orderByProperty] > b[orderByProperty] ? -1 : a[orderByProperty] < b[orderByProperty] ? 1 : 0;
                });
            } else {
                return data.data;
            }
        }, function(error) {
            sbxToast.showErrorMessage('Failed to load key values for anonymization key: ' + error);
        });

        return keyValuesPromise;
    };

    $scope.exportToCsv = function(keys) {
        var csv = 
            "Id;Image Id;Created;Patient Name;Anonymous Patient Name;Patient ID;Anonymous Patient ID" +
            "Study Instance UID;Anonymous Study Instance UID;Series Instance UID;Anonymous Series Instance UID;" +
            "SOP Instance UID;Anonymous SOP Instance UID\n" +
            keys.map(function (key) {
                return key.id + ";" + key.imageId + ";" + key.created + ";" + key.patientName + ";" + key.anonPatientName + ";" + key.patientID + ";" + key.anonPatientID + ";" +
                    key.studyInstanceUID + ";" + key.anonStudyInstanceUID + ";" + key.seriesInstanceUID + ";" + key.anonSeriesInstanceUID + ";" +
                    key.sopInstanceUID + ";" + key.anonSOPInstanceUID;
            }).join("\n");
        var anchor = "<a class='md-button md-primary' href='data:text/csv;charset=UTF-8," + encodeURIComponent(csv) + "' download='slicebox-anonymization-keys.csv'>Download CSV</a>";
        var textBoxHeader = '<h4>...or copy these values to the clipboard:</h4>';
        var textBox = "<md-content style='height: 200px;padding: 8px;'><pre>" + csv + "</pre></md-content>";
        var body = anchor + textBoxHeader + textBox;
        openMessageModal("Download or copy CSV", body);
    };

    function capitalizeFirst(string) {
        return string.charAt(0).toUpperCase() + string.substring(1);        
    }

});
