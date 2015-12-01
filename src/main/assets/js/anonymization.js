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

.controller('AnonymizationCtrl', function($scope, $http, $interval, openMessageModal, openDeleteEntitiesModalFunction, sbxToast) {
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
            }
        ];

    $scope.callbacks = {};

    // Scope functions
    $scope.loadAnonymizationKeyPage = function(startIndex, count, orderByProperty, orderByDirection, filter) {
        var loadUrl = '/api/anonymization/keys?startindex=' + startIndex + '&count=' + count;
        if (orderByProperty) {
            loadUrl = loadUrl + '&orderby=' + orderByProperty.toLowerCase();
            
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

    $scope.exportToCsv = function(keys) {
        var csv = 
            "Id;Created;Patient Birth Date;Patient Name;Anonymous Patient Name;Patient ID;Anonymous Patient ID;" + 
            "Study Instance UID;Anonymous Study Instance UID;Study Description;Study ID;Accession Number;" + 
            "Series Instance UID;Anonymous Series Instance UID;Series Description;Protocol Name;Frame Of Reference UID;Anonymous Frame Of Reference UID\n" +
            keys.map(function (key) {
                return key.id + ";" + key.created + ";" + key.patientBirthDate + ";" + key.patientName + ";" + key.anonPatientName + ";" + key.patientID + ";" + key.anonPatientID + ";" + 
                    key.studyInstanceUID + ";" + key.anonStudyInstanceUID + ";" + key.studyDescription + ";" + key.studyID + ";" + key.accessionNumber + ";" +
                    key.seriesInstanceUID + ";" + key.anonSeriesInstanceUID + ";" + key.seriesDescription + ";" + key.protocolName + ";" + key.frameOfReferenceUID + ";" + key.anonFrameOfReferenceUID;
            }).join("\n");
        var anchor = "<a class='md-button md-primary' href='data:text/csv;charset=UTF-8," + encodeURIComponent(csv) + "' download='slicebox-anonymization-keys.csv'>Download CSV</a>";
        var textBoxHeader = '<h4>...or copy these values to the clipboard:</h4>';
        var textBox = "<md-content style='height: 200px;padding: 8px;'><pre>" + csv + "</pre></md-content>";
        var body = anchor + textBoxHeader + textBox;
        openMessageModal("Download or copy CSV", body);
    };
});
